package io.github.yylsping.xadfree;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Bootstrap coordinator.
 *
 * <pre>
 * PackageReady → ATTACH_WAIT → SESSION → READY / DEGRADED
 * </pre>
 *
 * <p>X ships all of its DEX inside the base APK (17 dex files, no runtime
 * appends), so no runtime DEX observation is required: after
 * {@link Application#attach} the whole code base is visible through the
 * package ClassLoader and a single resolution session suffices.
 *
 * <p>The session prefers the per-identity descriptor cache (every entry
 * re-verified by reflection), falls back to DexKit tiers, and never hooks an
 * ambiguous candidate directly — ambiguous tops go through bounded
 * runtime-witness probes. READY requires at least one witnessed-path urt_emit
 * hook; DEGRADED leaves the app untouched (fail-open). The DexKit bridge and
 * the bootstrap attach hook are always released.
 */
final class HookCoordinator implements UrtEmitHooks.Listener, RuntimeWitness.Listener {
    private static final long DEADLINE_MILLIS = 20_000L;

    enum State { BOOTSTRAP, ATTACH_WAIT, SESSION, READY, DEGRADED }

    private final XposedModule module;
    private final ModuleLog log;
    private final String targetPackage;
    private final ClassLoader loader;
    private final AdDetector detector = new AdDetector(this::reportInspectionErrorOnce);
    private final UrtListFilter filter = new UrtListFilter(detector);
    private final UrtEmitHooks emitHooks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "xadfree-resolver-worker"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final AtomicBoolean sessionScheduled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final java.util.Set<String> reportedInspectionErrors =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile State state = State.BOOTSTRAP;
    private volatile Context appContext;
    private volatile XTargetIdentity identity;
    private volatile XResolutionCache cache;
    private volatile XDexKitSession dexKitSession;
    private volatile RuntimeWitness activeWitness;
    /** Descriptor → Method for witness promotions and direct installs. */
    private final Map<String, Method> methodsByDescriptor = new LinkedHashMap<>();
    private HookHandle attachHandle;
    private int installedHookCount;
    private long sessionStartMs;

    HookCoordinator(XposedModule module, ModuleLog log, String targetPackage,
                    ClassLoader loader) {
        this.module = module;
        this.log = log;
        this.targetPackage = targetPackage;
        this.loader = loader;
        this.emitHooks = new UrtEmitHooks(module, log, detector, filter, this);
    }

    /** Called from onPackageReady: installs the short-lived attach observer. */
    void install() {
        markState(State.ATTACH_WAIT, "packageReady loader=" + System.identityHashCode(loader));
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attachHandle = module.hook(attach)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("xadfree-application-attach")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object context = chain.getArg(0);
                        if (context instanceof Context) {
                            onApplicationAttached((Context) context);
                        }
                        return result;
                    });
            log.info("bootstrap attachObserver installed=true");
        } catch (Throwable throwable) {
            log.error("bootstrap attachObserver install failed", throwable);
            markDegraded("attachHookUnavailable");
        }
        mainHandler.postDelayed(() -> {
            if (!terminal.get()) {
                log.info("bootstrap watchdog fired elapsedMs=" + DEADLINE_MILLIS);
                worker.execute(this::finishSession);
            }
        }, DEADLINE_MILLIS);
    }

    private void onApplicationAttached(Context context) {
        HookHandle handle = attachHandle;
        attachHandle = null;
        if (handle != null) {
            try {
                handle.unhook();
                log.info("bootstrap attachObserver removed=true");
            } catch (Throwable ignored) {
            }
        }
        if (appContext != null) {
            return;
        }
        appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        if (sessionScheduled.compareAndSet(false, true)) {
            worker.execute(this::runSession);
        }
    }

    // ------------------------------------------------------------------
    // Resolution session (worker thread)
    // ------------------------------------------------------------------

    private void runSession() {
        markState(State.SESSION, "sessionStart");
        sessionStartMs = SystemClock.elapsedRealtime();
        try {
            identity = XTargetIdentity.compute(appContext, targetPackage);
            cache = new XResolutionCache(appContext);
            log.info("bootstrap identity " + identity.describe());

            Class<?> objectType = Object.class;
            Class<?> continuationType;
            try {
                continuationType = Class.forName(
                        XTargetVerifier.CONTINUATION_CLASS, false, loader);
            } catch (Throwable throwable) {
                markDegraded("continuationUnavailable");
                return;
            }

            boolean hooked = false;
            Map<String, ResolvedTarget> cached = cache.loadTargets(identity);
            if (!cached.isEmpty()) {
                log.info("cache hit=true targets=" + cached.size());
                hooked = installFromTargets(cached, objectType, continuationType);
            } else {
                log.info("cache hit=false reason=noEntryForIdentity");
            }

            if (!hooked) {
                resolveWithDexKit(objectType, continuationType);
            }
            finishSession();
        } catch (Throwable throwable) {
            log.error("bootstrap session failed", throwable);
            markDegraded("sessionException");
        }
    }

    /** Installs every still-valid cached target; drops only broken ones. */
    private boolean installFromTargets(Map<String, ResolvedTarget> targets,
                                       Class<?> objectType, Class<?> continuationType) {
        injectModelTargets(targets);
        boolean anyHooked = false;
        for (ResolvedTarget target : new ArrayList<>(targets.values())) {
            if (!XTargetResolver.isUrtEmitKey(target.key)) {
                continue;
            }
            XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                    target, loader, objectType, continuationType);
            if (verification.verdict == XTargetVerifier.Verdict.INVALID) {
                log.info("cache target=" + target.key + " invalid=true reason="
                        + verification.reason + " action=dropTarget");
                cache.removeTarget(identity, target.key);
                continue;
            }
            Method method = DescriptorUtils.methodForDescriptor(target.methodDescriptor, loader);
            if (method == null || emitHooks.install(method) == null) {
                cache.removeTarget(identity, target.key);
                continue;
            }
            methodsByDescriptor.put(target.methodDescriptor, method);
            installedHookCount++;
            anyHooked = true;
            log.info("hook target=" + target.key + " installed=true cacheHit=true tier=cache"
                    + " descriptor=" + target.methodDescriptor);
        }
        return anyHooked;
    }

    private void resolveWithDexKit(Class<?> objectType, Class<?> continuationType) {
        dexKitSession = new XDexKitSession(log, appContext, loader,
                this::supplyModuleInfo);
        try {
            org.luckypray.dexkit.DexKitBridge bridge =
                    dexKitSession.ensureBridge("session");
            if (bridge == null) {
                markDegraded("dexkitUnavailable");
                return;
            }
            UrtEmitResolver resolver = new UrtEmitResolver(
                    bridge, loader, log, objectType, continuationType);

            // Optional model targets first: they strengthen witness checks.
            Map<String, ResolvedTarget> resolved = new LinkedHashMap<>();
            ResolvedTarget modelInterface = resolver.resolveModelInterface();
            if (modelInterface != null) {
                resolved.put(modelInterface.key, modelInterface);
            }
            Class<?> modelClass = null;
            try {
                modelClass = Class.forName(UrtEmitResolver.MODEL_INTERFACE_NAME, false, loader);
                detector.setModelInterface(modelClass);
            } catch (Throwable ignored) {
            }
            ResolvedTarget adHelper = resolver.resolveAdHelper(modelClass);
            if (adHelper != null) {
                resolved.put(adHelper.key, adHelper);
            }
            injectModelTargets(resolved);

            List<CandidateScoring.ScoredCandidate> ranked = resolver.resolveEmitCandidates();
            if (ranked.isEmpty()) {
                cache.saveTargets(identity, resolved);
                markDegraded("urtEmitUnresolved");
                return;
            }

            CandidateScoring.ScoredCandidate top = ranked.get(0);
            boolean ambiguous = CandidateScoring.isAmbiguousTop(ranked);
            if (top.score >= CandidateScoring.ACCEPT_STATIC && !ambiguous) {
                ResolvedTarget target = new ResolvedTarget(
                        XTargetResolver.KEY_URT_EMIT, tierOf(top, ranked),
                        top.classDescriptor, top.methodDescriptor,
                        top.score, false);
                XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                        target, loader, objectType, continuationType);
                if (verification.verdict == XTargetVerifier.Verdict.INVALID) {
                    log.info("resolver target=urt_emit rejected=" + verification.reason
                            + " descriptor=" + top.methodDescriptor);
                    cache.saveTargets(identity, resolved);
                    markDegraded("topCandidateInvalid");
                    return;
                }
                Method method = DescriptorUtils.methodForDescriptor(top.methodDescriptor, loader);
                if (method != null && emitHooks.install(method) != null) {
                    methodsByDescriptor.put(top.methodDescriptor, method);
                    installedHookCount++;
                    resolved.put(target.key, target);
                    cache.saveTargets(identity, resolved);
                    log.info("hook target=urt_emit installed=true tier=dexkit"
                            + " descriptor=" + top.methodDescriptor
                            + " witness=inline");
                    return;
                }
                log.info("hook target=urt_emit installFailed descriptor=" + top.methodDescriptor);
                cache.saveTargets(identity, resolved);
                markDegraded("hookInstallFailed");
                return;
            }

            if (top.score >= CandidateScoring.ACCEPT_WITH_WITNESS || ambiguous) {
                startWitnessProbes(ranked, resolved, objectType, continuationType);
                return;
            }

            log.info("resolver target=urt_emit rejected=topScoreTooLow score=" + top.score);
            cache.saveTargets(identity, resolved);
            markDegraded("noCandidateAboveThreshold");
        } finally {
            // The witness path keeps only probes alive; the bridge itself is
            // no longer needed once candidates are reflected into Methods.
            dexKitSession.close();
        }
    }

    private void startWitnessProbes(List<CandidateScoring.ScoredCandidate> ranked,
                                    Map<String, ResolvedTarget> resolved,
                                    Class<?> objectType, Class<?> continuationType) {
        List<Method> probeMethods = new ArrayList<>();
        Map<String, ResolvedTarget> witnessTargets = new LinkedHashMap<>();
        for (CandidateScoring.ScoredCandidate candidate : ranked) {
            if (probeMethods.size() >= RuntimeWitness.MAX_PROBES) {
                break;
            }
            XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                    candidateToTarget(candidate), loader, objectType, continuationType);
            if (verification.verdict == XTargetVerifier.Verdict.INVALID) {
                log.info("candidate target=urt_emit rejected=probeVerify"
                        + " reason=" + verification.reason
                        + " descriptor=" + candidate.methodDescriptor);
                continue;
            }
            Method method = DescriptorUtils.methodForDescriptor(candidate.methodDescriptor, loader);
            if (method == null) {
                continue;
            }
            methodsByDescriptor.put(candidate.methodDescriptor, method);
            probeMethods.add(method);
            ResolvedTarget target = candidateToTarget(candidate);
            witnessTargets.put(target.key, target);
        }
        if (probeMethods.isEmpty()) {
            cache.saveTargets(identity, resolved);
            markDegraded("noProbeableCandidate");
            return;
        }
        // Persist model targets now; emit targets are persisted on promotion.
        cache.saveTargets(identity, resolved);
        RuntimeWitness witness = new RuntimeWitness(module, log, detector, this);
        activeWitness = witness;
        if (!witness.installProbes(ranked, probeMethods)) {
            activeWitness = null;
            markDegraded("probeInstallFailed");
            return;
        }
        // Session stays open until promotion/expiry; the worker quits but the
        // coordinator handles the callback on the main thread.
        log.info("bootstrap sessionWaitingForWitness=true probes=" + probeMethods.size());
    }

    private ResolvedTarget candidateToTarget(CandidateScoring.ScoredCandidate candidate) {
        return new ResolvedTarget(XTargetResolver.KEY_URT_EMIT, tierOf(candidate, null),
                candidate.classDescriptor, candidate.methodDescriptor,
                candidate.score, false);
    }

    private static String tierOf(CandidateScoring.ScoredCandidate candidate,
                                 List<CandidateScoring.ScoredCandidate> ranked) {
        // Both strings proven present is the strong tier by construction;
        // anything else that reached acceptance is weak.
        return candidate.evidence.contains("strings:both")
                ? XTargetResolver.TIER_STRONG : XTargetResolver.TIER_WEAK;
    }

    private void injectModelTargets(Map<String, ResolvedTarget> targets) {
        ResolvedTarget modelInterface = targets.get(XTargetResolver.KEY_MODEL_URT_ITEM);
        if (modelInterface != null && XTargetVerifier.rejectModelInterface(
                modelInterface, loader) == null) {
            try {
                detector.setModelInterface(DescriptorUtils.classForName(
                        modelInterface.classDescriptor, loader));
            } catch (Throwable ignored) {
            }
        }
        ResolvedTarget adHelper = targets.get(XTargetResolver.KEY_MODEL_AD_HELPER);
        if (adHelper != null) {
            Method method = DescriptorUtils.methodForDescriptor(adHelper.methodDescriptor, loader);
            if (method != null) {
                detector.setAppIsAd(method);
                log.info("detector appIsAd injected=true descriptor="
                        + adHelper.methodDescriptor);
            }
        }
    }

    // ------------------------------------------------------------------
    // Witness callbacks (main thread)
    // ------------------------------------------------------------------

    @Override
    public void onWitnessPromoted(String methodDescriptor) {
        RuntimeWitness witness = activeWitness;
        activeWitness = null;
        if (witness != null) {
            witness.cancel();
        }
        Method method = methodsByDescriptor.get(methodDescriptor);
        if (method == null) {
            log.info("witness target=urt_emit promotedWithoutMethod descriptor="
                    + methodDescriptor);
            markDegraded("promotedMethodMissing");
            return;
        }
        if (emitHooks.install(method) != null) {
            installedHookCount++;
            Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
            targets.put(XTargetResolver.KEY_URT_EMIT, new ResolvedTarget(
                    XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_WEAK,
                    DescriptorUtils.classDescriptorOf(method.getDeclaringClass()),
                    methodDescriptor, 0, true));
            try {
                Class<?> model = Class.forName(UrtEmitResolver.MODEL_INTERFACE_NAME, false, loader);
                targets.put(XTargetResolver.KEY_MODEL_URT_ITEM, new ResolvedTarget(
                        XTargetResolver.KEY_MODEL_URT_ITEM,
                        XTargetResolver.TIER_SEMANTIC_NAME,
                        DescriptorUtils.classDescriptorOf(model), "", 0, false));
            } catch (Throwable ignored) {
            }
            cache.saveTargets(identity, targets);
            log.info("hook target=urt_emit installed=true tier=witness"
                    + " descriptor=" + methodDescriptor);
            finishSession();
        } else {
            markDegraded("witnessInstallFailed");
        }
    }

    @Override
    public void onWitnessExpired() {
        activeWitness = null;
        markDegraded("witnessExpired");
    }

    @Override
    public void onWitnessPassed(String methodDescriptor) {
        // Inline witness of a directly installed hook: persist witnessed=true.
        Map<String, ResolvedTarget> targets = cache.loadTargets(identity);
        ResolvedTarget target = targets.get(XTargetResolver.KEY_URT_EMIT);
        if (target != null && !target.runtimeWitnessed) {
            targets.put(target.key, target.witnessed());
            cache.saveTargets(identity, targets);
            log.info("cache target=urt_emit witnessed=true descriptor=" + methodDescriptor);
        } else if (target == null) {
            Method method = methodsByDescriptor.get(methodDescriptor);
            if (method != null) {
                Map<String, ResolvedTarget> single = new LinkedHashMap<>();
                single.put(XTargetResolver.KEY_URT_EMIT, new ResolvedTarget(
                        XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                        DescriptorUtils.classDescriptorOf(method.getDeclaringClass()),
                        methodDescriptor, 0, true));
                cache.saveTargets(identity, single);
            }
        }
    }

    @Override
    public void onWitnessFailed(String methodDescriptor, String reason) {
        log.info("hook target=urt_emit unhooked=true reason=" + reason
                + " descriptor=" + methodDescriptor);
        installedHookCount = Math.max(0, installedHookCount - 1);
        Map<String, ResolvedTarget> targets = cache.loadTargets(identity);
        boolean removed = false;
        for (ResolvedTarget target : new ArrayList<>(targets.values())) {
            if (XTargetResolver.isUrtEmitKey(target.key)
                    && methodDescriptor.equals(target.methodDescriptor)) {
                cache.removeTarget(identity, target.key);
                removed = true;
            }
        }
        log.info("cache target=urt_emit invalidated=" + removed + " reason=" + reason);
        if (installedHookCount == 0) {
            markDegraded("lastHookDisarmed");
        }
    }

    // ------------------------------------------------------------------
    // Terminal states
    // ------------------------------------------------------------------

    private void finishSession() {
        if (installedHookCount > 0) {
            markState(State.READY, "hooks=" + installedHookCount
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - sessionStartMs));
        } else {
            markDegraded("sessionFinishedWithoutHooks");
        }
        shutdownWorker();
    }

    private void markDegraded(String reason) {
        markState(State.DEGRADED, "reason=" + reason
                + " hooks=" + installedHookCount
                + " elapsedMs=" + (sessionStartMs == 0 ? -1
                : SystemClock.elapsedRealtime() - sessionStartMs));
        shutdownWorker();
    }

    private void markState(State next, String detail) {
        synchronized (stateLock) {
            state = next;
        }
        if (next == State.READY || next == State.DEGRADED) {
            boolean first = terminal.compareAndSet(false, true);
            if (first) {
                mainHandler.removeCallbacksAndMessages(null);
                RuntimeWitness witness = activeWitness;
                if (witness != null && next == State.DEGRADED) {
                    witness.cancel();
                    activeWitness = null;
                }
            }
        }
        log.info("bootstrap state=" + next + " " + detail);
    }

    private void shutdownWorker() {
        try {
            worker.shutdown();
        } catch (Throwable ignored) {
        }
    }

    private void reportInspectionErrorOnce(Class<?> modelClass, Throwable error) {
        String key = modelClass.getName() + ':' + error.getClass().getName();
        if (reportedInspectionErrors.add(key)) {
            log.error("detector inspection failed for " + modelClass.getName()
                    + " (once per class+error)", error);
        }
    }

    /** Framework-provided module info; bypasses package-visibility filtering. */
    private android.content.pm.ApplicationInfo supplyModuleInfo() {
        try {
            return module.getModuleApplicationInfo();
        } catch (Throwable throwable) {
            log.info("resolver moduleInfoUnavailable reason=" + throwable);
            return null;
        }
    }
}
