package io.github.yylsping.xadfree;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Event-driven bootstrap coordinator.
 *
 * <pre>
 * BOOTSTRAP → ATTACH_WAIT → RESOLVING ──unique safe──→ READY(hook installed,
 *                 │              │        self-check pending)
 *                 │              ├─ambiguous/weak─→ WAITING_WITNESS
 *                 │              │                      ↓ promote / expire
 *                 │              └─no safe target──→ DEGRADED (fail-open)
 *                 └─deadline (20s, resolve phase only)
 * </pre>
 *
 * <p>All state is owned by one serial worker lane (P1-4): hook callbacks
 * and witness callbacks only post events; timers post events. The lane is a
 * {@code ThreadPoolExecutor(0, 1, keepAlive)} whose idle worker thread exits
 * on its own (2.0.2) — no private thread lingers after READY once events
 * settle, and any future legal event (late self-check, helper witness,
 * hook failure) transparently restarts exactly one serial worker. The
 * executor itself is never shut down. READY and
 * DEGRADED are frozen (P1-5) — the only permitted post-terminal transition is
 * the safety demotion READY → DEGRADED when a runtime witness disarms the
 * last installed hook. Every event carries the session id that produced it;
 * events from older sessions are ignored.
 *
 * <p>Phase-scoped deadlines (P0-2): the 20s bootstrap watchdog covers
 * BOOTSTRAP/ATTACH_WAIT/RESOLVING only and is cancelled when probes start;
 * the witness owns its own 30s probe deadline. A WAITING_WITNESS session is
 * never finished by the resolver — probe completion is driven solely by
 * promotion, expiry or cancellation events (P0-1).
 *
 * <p>READY means "hook installed and bootstrap complete"; the inline witness
 * may still be performing its post-install self-validation (P3-2 semantics).
 */
class HookCoordinator implements UrtEmitHooks.Listener, RuntimeWitness.Listener,
        AdDetector.AdHelperWitnessListener {
    static final long BOOTSTRAP_DEADLINE_MILLIS = 20_000L;
    /** Idle seconds before the serial worker thread exits (2.0.2). */
    static final long WORKER_KEEPALIVE_SECONDS = 10L;
    private static final String TOKEN_BOOTSTRAP_DEADLINE = "bootstrap-deadline";

    enum State { BOOTSTRAP, ATTACH_WAIT, RESOLVING, WAITING_WITNESS, READY, DEGRADED }

    /** Explicit result of one resolution pass; no side-effect-only states. */
    enum ResolveOutcomeType { RESOLVED_AND_HOOKED, WAITING_RUNTIME_WITNESS, NO_SAFE_TARGET }

    static final class ResolveOutcome {
        final ResolveOutcomeType type;
        final String reason;

        static ResolveOutcome hooked(String reason) {
            return new ResolveOutcome(ResolveOutcomeType.RESOLVED_AND_HOOKED, reason);
        }

        static ResolveOutcome waiting() {
            return new ResolveOutcome(ResolveOutcomeType.WAITING_RUNTIME_WITNESS, "probesActive");
        }

        static ResolveOutcome noSafeTarget(String reason) {
            return new ResolveOutcome(ResolveOutcomeType.NO_SAFE_TARGET, reason);
        }

        ResolveOutcome(ResolveOutcomeType type, String reason) {
            this.type = type;
            this.reason = reason;
        }
    }

    /** Injectable per-session components (production casts to Context). */
    interface SessionComponents {
        XTargetIdentity createIdentity(Object appContext);

        XResolutionCache createCache(Object appContext);

        XDexKitSession createDexKitSession(Object appContext);
    }

    private final ModuleLog log;
    private final String targetPackage;
    private final ClassLoader loader;
    private final HookFramework framework;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final DexKitNativeLoader.ModuleInfoSupplier moduleInfoSupplier;
    private final SessionComponents components;

    private final AdDetector detector = new AdDetector(this::reportInspectionErrorOnce, this);
    private final UrtListFilter filter = new UrtListFilter(detector);
    private final UrtEmitHooks emitHooks;
    /**
     * Serial event lane. corePoolSize 0 + maximumPoolSize 1 keeps every
     * coordinator event strictly serial while letting the worker thread die
     * after {@link #WORKER_KEEPALIVE_SECONDS} idle seconds (P1-1, 2.0.2).
     */
    private final ExecutorService worker;

    private final Object stateLock = new Object();
    private final AtomicBoolean sessionScheduled = new AtomicBoolean();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final java.util.Set<String> reportedInspectionErrors =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Worker-owned state (written and read on the worker thread only).
    private State state = State.BOOTSTRAP;
    private boolean terminal;
    private volatile long activeSessionId;
    private long sessionStartElapsed;
    private Object appContext;
    private XTargetIdentity identity;
    private XResolutionCache cache;
    private volatile RuntimeWitness activeWitness;
    private long witnessSessionId;
    private final Map<String, Method> probeMethodsByDescriptor = new LinkedHashMap<>();

    // Cross-thread handles.
    private volatile HookFramework.HookHandle attachHandle;

    HookCoordinator(ModuleLog log, String targetPackage, ClassLoader loader,
                    HookFramework framework, Scheduler scheduler, LongSupplier clock,
                    DexKitNativeLoader.ModuleInfoSupplier moduleInfoSupplier,
                    SessionComponents components) {
        this.log = log;
        this.targetPackage = targetPackage;
        this.loader = loader;
        this.framework = framework;
        this.scheduler = scheduler;
        this.clock = clock;
        this.moduleInfoSupplier = moduleInfoSupplier;
        this.components = components;
        this.emitHooks = new UrtEmitHooks(framework, log, detector, filter, this);
        this.worker = createWorker();
    }

    /**
     * The serial event lane. Overridable so JVM tests can inject a short
     * keepAlive; production uses an idle-exiting 0/1 pool.
     */
    ExecutorService createWorker() {
        return new java.util.concurrent.ThreadPoolExecutor(
                0, 1, WORKER_KEEPALIVE_SECONDS, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "xadfree-resolver-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Called from onPackageReady: installs the short-lived attach observer. */
    void install() {
        transition(State.ATTACH_WAIT, "packageReady loader=" + System.identityHashCode(loader));
        try {
            Method attach = attachObserverMethod();
            attachHandle = framework.hook(attach, "xadfree-application-attach", invocation -> {
                Object result = invocation.proceed();
                Object context = invocation.getArg(0);
                if (context != null) {
                    onApplicationAttached(context);
                }
                return result;
            });
            if (attachHandle == null) {
                throw new IllegalStateException("framework refused attach hook");
            }
            log.info("bootstrap attachObserver installed=true");
        } catch (Throwable throwable) {
            log.error("bootstrap attachObserver install failed", throwable);
            post("attach-failed", 0, () -> degrade("attachHookUnavailable"));
            return;
        }
        scheduler.postDelayed(TOKEN_BOOTSTRAP_DEADLINE,
                () -> post("bootstrap-deadline", 0, this::onBootstrapDeadline),
                BOOTSTRAP_DEADLINE_MILLIS);
    }

    /**
     * Application.attach is package-private Android API: present at runtime,
     * absent from the JVM android.jar stub. Tests override this seam with a
     * fixture method.
     */
    Method attachObserverMethod() throws NoSuchMethodException {
        return Application.class.getDeclaredMethod("attach", Context.class);
    }

    /** The Continuation type resolved through the target loader. */
    Class<?> continuationTypeForSession() throws ClassNotFoundException {
        return Class.forName(XTargetVerifier.CONTINUATION_CLASS, false, loader);
    }

    private void onApplicationAttached(Object context) {
        HookFramework.HookHandle handle = attachHandle;
        attachHandle = null;
        if (handle != null) {
            try {
                handle.unhook();
                log.info("bootstrap attachObserver removed=true");
            } catch (Throwable ignored) {
            }
        }
        post("attach", 0, () -> {
            if (appContext != null) {
                return; // Secondary attach (e.g. instrumentation): ignore.
            }
            appContext = context;
            if (sessionScheduled.compareAndSet(false, true)) {
                runSession();
            }
        });
    }

    private void onBootstrapDeadline() {
        if (terminal) {
            return; // A session already finished; the timer is stale.
        }
        if (state == State.WAITING_WITNESS) {
            return; // Probe deadline owns this phase (P0-2).
        }
        degrade("bootstrapDeadline");
    }

    // ------------------------------------------------------------------
    // Resolution session (worker thread)
    // ------------------------------------------------------------------

    private void runSession() {
        long sessionId = sessionSequence.incrementAndGet();
        activeSessionId = sessionId;
        sessionStartElapsed = clock.getAsLong();
        transition(State.RESOLVING, "sessionStart");

        ResolveOutcome outcome;
        try {
            identity = components.createIdentity(appContext);
            cache = components.createCache(appContext);
            log.info("bootstrap session=" + sessionId + " identity " + identity.describe());

            Class<?> objectType = Object.class;
            Class<?> continuationType;
            try {
                continuationType = continuationTypeForSession();
            } catch (Throwable throwable) {
                outcome = ResolveOutcome.noSafeTarget("continuationUnavailable");
                dispatchOutcome(outcome);
                return;
            }

            ResolveOutcome cachedOutcome = tryInstallFromCache(objectType, continuationType);
            if (cachedOutcome != null) {
                outcome = cachedOutcome;
            } else {
                outcome = resolveWithDexKit(objectType, continuationType);
            }
        } catch (Throwable throwable) {
            log.error("bootstrap session failed", throwable);
            outcome = ResolveOutcome.noSafeTarget("sessionException");
        }
        dispatchOutcome(outcome);
    }

    private void dispatchOutcome(ResolveOutcome outcome) {
        switch (outcome.type) {
            case RESOLVED_AND_HOOKED:
                finishReady(outcome.reason);
                break;
            case WAITING_RUNTIME_WITNESS:
                // Keep the session open; probes drive completion (P0-1).
                scheduler.cancel(TOKEN_BOOTSTRAP_DEADLINE);
                transition(State.WAITING_WITNESS, "probes="
                        + probeMethodsByDescriptor.size());
                break;
            case NO_SAFE_TARGET:
            default:
                degrade(outcome.reason);
                break;
        }
    }

    /** Cache path: null when the cache held no usable emit target. */
    private ResolveOutcome tryInstallFromCache(Class<?> objectType, Class<?> continuationType) {
        Map<String, ResolvedTarget> cached = cache.loadTargets(identity);
        if (cached.isEmpty()) {
            log.info("cache hit=false reason=noEntryForIdentity");
            return null;
        }
        log.info("cache hit=true targets=" + cached.size());
        injectModelTargets(cached, true);

        boolean anyHooked = false;
        for (ResolvedTarget target : new ArrayList<>(cached.values())) {
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
            if (method == null || !emitHooks.install(method)) {
                cache.removeTarget(identity, target.key);
                continue;
            }
            anyHooked = true;
            log.info("hook target=urt_emit installed=true cacheHit=true tier=cache"
                    + " descriptor=" + target.methodDescriptor
                    + " runtimeSelfCheck="
                    + (target.runtimeWitnessed ? "cachedWitnessed" : "pending"));
        }
        return anyHooked ? ResolveOutcome.hooked("cacheHit") : null;
    }

    ResolveOutcome resolveWithDexKit(Class<?> objectType, Class<?> continuationType) {
        XDexKitSession dexKitSession = components.createDexKitSession(appContext);
        try {
            org.luckypray.dexkit.DexKitBridge bridge =
                    dexKitSession.ensureBridge("session");
            if (bridge == null) {
                return ResolveOutcome.noSafeTarget("dexkitUnavailable");
            }
            UrtEmitResolver resolver = new UrtEmitResolver(
                    bridge, loader, log, objectType, continuationType);

            // Optional model targets first: they strengthen witness checks.
            Map<String, ResolvedTarget> resolvedModels = new LinkedHashMap<>();
            ResolvedTarget modelInterface = resolver.resolveModelInterface();
            if (modelInterface != null) {
                resolvedModels.put(modelInterface.key, modelInterface);
            }
            Class<?> modelClass = null;
            try {
                modelClass = Class.forName(UrtEmitResolver.MODEL_INTERFACE_NAME, false, loader);
            } catch (Throwable ignored) {
            }
            ResolvedTarget adHelper = resolver.resolveAdHelper(modelClass);
            if (adHelper != null) {
                resolvedModels.put(adHelper.key, adHelper);
            }
            injectModelTargets(resolvedModels, false);

            List<CandidateScoring.ScoredCandidate> ranked = resolver.resolveEmitCandidates();
            if (ranked.isEmpty()) {
                persistTargets(resolvedModels);
                return ResolveOutcome.noSafeTarget("urtEmitUnresolved");
            }

            CandidateScoring.ScoredCandidate top = ranked.get(0);
            boolean ambiguous = CandidateScoring.isAmbiguousTop(ranked);
            int margin = ranked.size() > 1 ? top.score - ranked.get(1).score : top.score;
            log.info("resolver target=urt_emit top score=" + top.score
                    + " margin=" + margin + " ambiguous=" + ambiguous
                    + " candidateCount=" + ranked.size());

            if (top.score >= CandidateScoring.ACCEPT_STATIC && !ambiguous) {
                XTargetVerifier.Verification verification = XTargetVerifier.verifyUrtEmit(
                        candidateToTarget(top), loader, objectType, continuationType);
                if (verification.verdict == XTargetVerifier.Verdict.INVALID) {
                    log.info("resolver target=urt_emit rejected=" + verification.reason
                            + " descriptor=" + top.methodDescriptor);
                    persistTargets(resolvedModels);
                    return ResolveOutcome.noSafeTarget("topCandidateInvalid");
                }
                Method method = DescriptorUtils.methodForDescriptor(top.methodDescriptor, loader);
                if (method == null || !emitHooks.install(method)) {
                    persistTargets(resolvedModels);
                    return ResolveOutcome.noSafeTarget("hookInstallFailed");
                }
                Map<String, ResolvedTarget> all = new LinkedHashMap<>(resolvedModels);
                all.put(XTargetResolver.KEY_URT_EMIT, candidateToTarget(top));
                persistTargets(all);
                return ResolveOutcome.hooked("dexkitDirect");
            }

            if (top.score >= CandidateScoring.ACCEPT_WITH_WITNESS || ambiguous) {
                return startWitnessProbes(ranked, resolvedModels,
                        objectType, continuationType);
            }

            persistTargets(resolvedModels);
            return ResolveOutcome.noSafeTarget("topScoreTooLow score=" + top.score);
        } finally {
            // The witness path keeps only observe-only probes alive; the
            // bridge itself is released once candidates became Methods.
            dexKitSession.close();
        }
    }

    ResolveOutcome startWitnessProbes(List<CandidateScoring.ScoredCandidate> ranked,
                                              Map<String, ResolvedTarget> resolvedModels,
                                              Class<?> objectType, Class<?> continuationType) {
        probeMethodsByDescriptor.clear();
        for (CandidateScoring.ScoredCandidate candidate : ranked) {
            if (probeMethodsByDescriptor.size() >= RuntimeWitness.MAX_PROBES) {
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
            probeMethodsByDescriptor.put(candidate.methodDescriptor, method);
        }
        if (probeMethodsByDescriptor.isEmpty()) {
            persistTargets(resolvedModels);
            return ResolveOutcome.noSafeTarget("noProbeableCandidate");
        }
        // Persist model targets now; emit targets persist on promotion only.
        persistTargets(resolvedModels);

        RuntimeWitness witness = new RuntimeWitness(framework, log, detector, this, scheduler);
        activeWitness = witness;
        witnessSessionId = activeSessionId;
        if (!witness.installProbes(new ArrayList<>(probeMethodsByDescriptor.values()))) {
            activeWitness = null;
            return ResolveOutcome.noSafeTarget("probeInstallFailed");
        }
        log.info("bootstrap sessionWaitingForWitness=true probes="
                + probeMethodsByDescriptor.size() + " session=" + activeSessionId);
        return ResolveOutcome.waiting();
    }

    private ResolvedTarget candidateToTarget(CandidateScoring.ScoredCandidate candidate) {
        return new ResolvedTarget(XTargetResolver.KEY_URT_EMIT,
                candidate.evidence.contains("strings:both")
                        ? XTargetResolver.TIER_STRONG : XTargetResolver.TIER_WEAK,
                candidate.classDescriptor, candidate.methodDescriptor,
                candidate.score, false);
    }

    /**
     * Model-target injection. Cached and freshly resolved targets pass the
     * exact same verification (P0-3 layer 1); a cached target that no longer
     * verifies is dropped from the cache, and the ad helper is only injected
     * as an UNVERIFIED supporting signal (P0-3 layer 2).
     */
    private void injectModelTargets(Map<String, ResolvedTarget> targets, boolean fromCache) {
        ResolvedTarget modelInterface = targets.get(XTargetResolver.KEY_MODEL_URT_ITEM);
        if (modelInterface != null) {
            String rejected = XTargetVerifier.rejectModelInterface(modelInterface, loader);
            if (rejected == null) {
                try {
                    detector.setModelInterface(DescriptorUtils.classForName(
                            modelInterface.classDescriptor, loader));
                } catch (Throwable ignored) {
                }
            } else if (fromCache) {
                log.info("cache target=model.urtItemInterface invalid=true reason=" + rejected);
                cache.removeTarget(identity, modelInterface.key);
            }
        }

        ResolvedTarget adHelper = targets.get(XTargetResolver.KEY_MODEL_AD_HELPER);
        if (adHelper == null) {
            return;
        }
        Class<?> modelClass = null;
        try {
            modelClass = Class.forName(UrtEmitResolver.MODEL_INTERFACE_NAME, false, loader);
        } catch (Throwable ignored) {
        }
        String rejected = XTargetVerifier.rejectAdHelper(adHelper, loader, modelClass);
        if (rejected != null) {
            if (fromCache) {
                log.info("cache target=model.adHelperIsAd invalid=true reason=" + rejected
                        + " action=dropTarget");
                cache.removeTarget(identity, adHelper.key);
            }
            return;
        }
        Method method = DescriptorUtils.methodForDescriptor(adHelper.methodDescriptor, loader);
        if (method != null) {
            detector.setAppIsAd(method);
            log.info("detector appIsAd injected=true unverifiedWeight="
                    + AdDetector.APP_HELPER_WEIGHT_UNVERIFIED
                    + " descriptor=" + adHelper.methodDescriptor);
        }
    }

    /** Merges new targets into the identity's stored set (never replaces). */
    private void persistTargets(Map<String, ResolvedTarget> fresh) {
        if (fresh.isEmpty()) {
            return;
        }
        Map<String, ResolvedTarget> merged = cache.loadTargets(identity);
        XTargetResolver.mergeTargets(merged, fresh);
        cache.saveTargets(identity, merged);
    }

    // ------------------------------------------------------------------
    // Witness callbacks (hooked threads → posted to the worker)
    // ------------------------------------------------------------------

    @Override
    public void onWitnessPromoted(String methodDescriptor, String evidenceSummary) {
        long sessionId = witnessSessionId;
        post("witness-promoted", sessionId,
                () -> handleWitnessPromoted(sessionId, methodDescriptor, evidenceSummary));
    }

    @Override
    public void onWitnessExpired(String reason) {
        long sessionId = witnessSessionId;
        post("witness-expired", sessionId, () -> handleWitnessExpired(sessionId, reason));
    }

    private void handleWitnessPromoted(long sessionId, String methodDescriptor,
                                       String evidenceSummary) {
        if (sessionId != activeSessionId || terminal) {
            log.info("witness promote ignored stale session=" + sessionId
                    + " active=" + activeSessionId + " terminal=" + terminal);
            return;
        }
        RuntimeWitness witness = activeWitness;
        activeWitness = null;
        if (witness != null) {
            witness.cancel(); // Idempotent: settle already happened.
        }
        Method method = probeMethodsByDescriptor.get(methodDescriptor);
        if (method == null) {
            degrade("promotedMethodMissing");
            return;
        }
        if (!emitHooks.install(method)) {
            degrade("witnessInstallFailed");
            return;
        }
        cache.updateTarget(identity, new ResolvedTarget(
                XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_RUNTIME_WITNESS,
                DescriptorUtils.classDescriptorOf(method.getDeclaringClass()),
                methodDescriptor, 0, true));
        log.info("hook target=urt_emit installed=true tier=runtime_witness"
                + " descriptor=" + methodDescriptor + " " + evidenceSummary);
        finishReady("witnessPromoted");
    }

    private void handleWitnessExpired(long sessionId, String reason) {
        if (sessionId != activeSessionId || terminal) {
            log.info("witness expire ignored stale session=" + sessionId
                    + " active=" + activeSessionId + " terminal=" + terminal);
            return;
        }
        degrade("witnessExpired:" + reason);
    }

    // ------------------------------------------------------------------
    // Inline hook witness callbacks (hooked threads → posted to the worker)
    // ------------------------------------------------------------------

    @Override
    public void onWitnessPassed(String methodDescriptor) {
        post("hook-witness-passed", activeSessionId,
                () -> handleHookWitnessPassed(methodDescriptor));
    }

    private void handleHookWitnessPassed(String methodDescriptor) {
        if (identity == null || cache == null) {
            return;
        }
        Map<String, ResolvedTarget> targets = cache.loadTargets(identity);
        for (ResolvedTarget target : new ArrayList<>(targets.values())) {
            if (XTargetResolver.isUrtEmitKey(target.key)
                    && methodDescriptor.equals(target.methodDescriptor)
                    && !target.runtimeWitnessed) {
                cache.updateTarget(identity, target.witnessed());
                log.info("cache target=urt_emit witnessed=true runtimeSelfCheck=passed"
                        + " descriptor=" + methodDescriptor);
                return;
            }
        }
        Method method = emitHooks.methodOf(methodDescriptor);
        if (method != null) {
            cache.updateTarget(identity, new ResolvedTarget(
                    XTargetResolver.KEY_URT_EMIT, XTargetResolver.TIER_STRONG,
                    DescriptorUtils.classDescriptorOf(method.getDeclaringClass()),
                    methodDescriptor, 0, true));
            log.info("cache target=urt_emit witnessed=true runtimeSelfCheck=passed"
                    + " descriptor=" + methodDescriptor);
        }
    }

    @Override
    public void onWitnessFailed(String methodDescriptor, String reason) {
        long sessionId = activeSessionId;
        post("hook-witness-failed", sessionId,
                () -> handleHookWitnessFailed(sessionId, methodDescriptor, reason));
    }

    private void handleHookWitnessFailed(long sessionId, String methodDescriptor, String reason) {
        if (sessionId != activeSessionId) {
            return; // Old generation (P1-5).
        }
        // UrtEmitHooks already unhooked itself (P1-3); drop its cache target.
        boolean removedAny = false;
        Map<String, ResolvedTarget> targets = cache.loadTargets(identity);
        for (ResolvedTarget target : new ArrayList<>(targets.values())) {
            if (XTargetResolver.isUrtEmitKey(target.key)
                    && methodDescriptor.equals(target.methodDescriptor)) {
                cache.removeTarget(identity, target.key);
                removedAny = true;
            }
        }
        log.info("cache target=urt_emit invalidated=" + removedAny + " reason=" + reason);
        if (!emitHooks.hasInstalled()) {
            // Safety demotion: READY → DEGRADED is the one allowed
            // post-terminal transition (P1-5).
            degrade("lastHookDisarmed:" + reason);
        }
    }

    // ------------------------------------------------------------------
    // Ad-helper semantic witness callbacks (hooked threads → worker)
    // ------------------------------------------------------------------

    @Override
    public void onAdHelperVerified(String evidenceSummary) {
        post("adhelper-verified", activeSessionId, () -> log.info(
                "detector appIsAd semanticWitness=verified weight="
                        + AdDetector.APP_HELPER_WEIGHT_VERIFIED + " " + evidenceSummary));
    }

    @Override
    public void onAdHelperDisabled(String reason) {
        long sessionId = activeSessionId;
        post("adhelper-disabled", sessionId, () -> {
            log.info("detector appIsAd semanticWitness=disabled reason=" + reason
                    + " failOpen=true");
            if (identity != null && cache != null && sessionId == activeSessionId) {
                cache.removeTarget(identity, XTargetResolver.KEY_MODEL_AD_HELPER);
            }
        });
    }

    // ------------------------------------------------------------------
    // Terminal transitions and timers (worker thread)
    // ------------------------------------------------------------------

    private void finishReady(String reason) {
        String detail = "reason=" + reason
                + " hooks=" + emitHooks.installedCount()
                + " runtimeSelfCheck="
                + ("witnessPromoted".equals(reason) ? "passed" : "pending")
                + " elapsedMs=" + elapsed();
        transition(State.READY, detail);
        // The idle-exit worker lane tears itself down after keepAlive; no
        // explicit shutdown — future legal events must still be servable.
    }

    private void degrade(String reason) {
        String detail = "reason=" + reason
                + " hooks=" + emitHooks.installedCount()
                + " elapsedMs=" + elapsed()
                + " failOpen=true";
        if (transition(State.DEGRADED, detail)) {
            RuntimeWitness witness = activeWitness;
            activeWitness = null;
            if (witness != null) {
                witness.cancel();
            }
            HookFramework.HookHandle handle = attachHandle;
            attachHandle = null;
            if (handle != null) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Frozen state machine (P1-5): once READY/DEGRADED is entered, stale
     * timers, late witness callbacks and old-session results can no longer
     * move the state — except the READY → DEGRADED safety demotion when the
     * runtime witness disarms the last hook.
     */
    private boolean transition(State next, String detail) {
        boolean firstTerminal = false;
        synchronized (stateLock) {
            if (terminal) {
                boolean safetyDemotion = state == State.READY && next == State.DEGRADED;
                if (!safetyDemotion) {
                    return false;
                }
            }
            if (state == next && next != State.RESOLVING) {
                return false;
            }
            state = next;
            if (next == State.READY || next == State.DEGRADED) {
                terminal = true;
                firstTerminal = true;
            }
        }
        if (firstTerminal) {
            scheduler.cancel(TOKEN_BOOTSTRAP_DEADLINE);
        }
        log.info("bootstrap state=" + next + " session=" + activeSessionId + " " + detail);
        return true;
    }

    private long elapsed() {
        return sessionStartElapsed == 0L ? -1L : clock.getAsLong() - sessionStartElapsed;
    }

    // ------------------------------------------------------------------
    // Event plumbing
    // ------------------------------------------------------------------

    private void post(String what, long sessionId, Runnable body) {
        try {
            worker.execute(() -> {
                if (sessionId != 0L && sessionId != activeSessionId) {
                    log.info("event ignored stale what=" + what
                            + " session=" + sessionId + " active=" + activeSessionId);
                    return;
                }
                try {
                    body.run();
                } catch (Throwable throwable) {
                    log.error("event failed what=" + what, throwable);
                }
            });
        } catch (Throwable rejected) {
            // Defensive only: the idle-exit pool is never shut down.
            log.info("event dropped executorRejected what=" + what);
        }
    }

    private void reportInspectionErrorOnce(Class<?> modelClass, Throwable error) {
        String key = modelClass.getName() + ':' + error.getClass().getName();
        if (reportedInspectionErrors.add(key)) {
            log.error("detector inspection failed for " + modelClass.getName()
                    + " (once per class+error)", error);
        }
    }

    // ------------------------------------------------------------------
    // JVM-test accessors
    // ------------------------------------------------------------------

    /** Replays the bootstrap-deadline timer event (P0-2 tests). */
    void fireBootstrapDeadlineForTests() {
        post("bootstrap-deadline", 0, this::onBootstrapDeadline);
    }

    State stateForTests() {
        synchronized (stateLock) {
            return state;
        }
    }

    boolean terminalForTests() {
        synchronized (stateLock) {
            return terminal;
        }
    }

    RuntimeWitness activeWitnessForTests() {
        return activeWitness;
    }

    /** Live worker threads of the serial lane (0 after idle exit). */
    int workerPoolSizeForTests() {
        if (worker instanceof java.util.concurrent.ThreadPoolExecutor) {
            return ((java.util.concurrent.ThreadPoolExecutor) worker).getPoolSize();
        }
        return -1;
    }

    /** Blocks until every queued worker event has run. */
    void awaitWorkerIdleForTests() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try {
            worker.execute(latch::countDown);
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("worker stuck");
            }
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Worker already shut down: nothing pending.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Production entry: binds the libxposed adapter and real components. */
    static HookCoordinator forProduction(io.github.libxposed.api.XposedModule module,
                                         ModuleLog log, String targetPackage,
                                         ClassLoader loader) {
        DexKitNativeLoader.ModuleInfoSupplier moduleInfoSupplier = () -> {
            try {
                return module.getModuleApplicationInfo();
            } catch (Throwable throwable) {
                log.info("resolver moduleInfoUnavailable reason=" + throwable);
                return null;
            }
        };
        SessionComponents components = new SessionComponents() {
            @Override
            public XTargetIdentity createIdentity(Object appContext) {
                return XTargetIdentity.compute((Context) appContext, targetPackage);
            }

            @Override
            public XResolutionCache createCache(Object appContext) {
                return new XResolutionCache((Context) appContext);
            }

            @Override
            public XDexKitSession createDexKitSession(Object appContext) {
                return new XDexKitSession(log, (Context) appContext, loader, moduleInfoSupplier);
            }
        };
        return new HookCoordinator(log, targetPackage, loader,
                new XposedHookFramework(module), new Scheduler.HandlerScheduler(),
                android.os.SystemClock::elapsedRealtime, moduleInfoSupplier, components);
    }
}
