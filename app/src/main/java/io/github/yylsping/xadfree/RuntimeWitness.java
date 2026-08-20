package io.github.yylsping.xadfree;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Short-lived probe witness for ambiguous static resolutions: DexKit narrowed
 * the field but the top scores are too close (or too weak) to pick a winner.
 *
 * <p>Up to {@link #MAX_PROBES} candidates receive a temporary observe-only
 * hook (no filtering, no arg mutation). The first candidate whose real
 * invocation delivers a List with URT-entry-shaped elements wins and is
 * promoted through {@link Listener#onWitnessPromoted}; every probe is then
 * unhooked. A bounded lifetime timer expires the whole set fail-open.
 */
final class RuntimeWitness {
    static final int MAX_PROBES = 5;
    static final long PROBE_TIMEOUT_MILLIS = 30_000L;

    interface Listener {
        void onWitnessPromoted(String methodDescriptor);
        void onWitnessExpired();
    }

    private final XposedModule module;
    private final ModuleLog log;
    private final AdDetector detector;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, HookHandle> probeHandles = new HashMap<>();
    private final Map<String, Strike> strikes = new HashMap<>();
    private boolean finished;

    RuntimeWitness(XposedModule module, ModuleLog log, AdDetector detector,
                   Listener listener) {
        this.module = module;
        this.log = log;
        this.detector = detector;
        this.listener = listener;
    }

    /** Installs observe-only probes on the top ambiguous candidates. */
    boolean installProbes(List<CandidateScoring.ScoredCandidate> candidates,
                          List<Method> methods) {
        if (finished || methods == null || methods.isEmpty()) {
            return false;
        }
        int installed = 0;
        for (Method method : methods) {
            if (installed >= MAX_PROBES) {
                break;
            }
            String descriptor = UrtEmitResolver.dexDescriptorOf(method);
            if (probeHandles.containsKey(descriptor)) {
                continue;
            }
            try {
                HookHandle handle = module.hook(method)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("xadfree-urt-emit-probe")
                        .intercept(chain -> {
                            observe(descriptor, chain.getArg(0));
                            return chain.proceed();
                        });
                probeHandles.put(descriptor, handle);
                strikes.put(descriptor, new Strike());
                installed++;
                log.info("witness target=urt_emit state=probeInstalled descriptor="
                        + descriptor);
            } catch (Throwable throwable) {
                log.error("witness target=urt_emit probeInstallFailed descriptor="
                        + descriptor, throwable);
            }
        }
        if (installed == 0) {
            return false;
        }
        log.info("witness target=urt_emit state=probing probes=" + installed
                + " timeoutMs=" + PROBE_TIMEOUT_MILLIS);
        mainHandler.postDelayed(this::expire, PROBE_TIMEOUT_MILLIS);
        return true;
    }

    private void observe(String descriptor, Object argument) {
        if (finished) {
            return;
        }
        List<?> sample = argument instanceof List<?> ? (List<?>) argument : null;
        boolean shaped = sample != null && firstShapedElement(sample) != null;
        WitnessLogic.Decision decision = WitnessLogic.onInvocation(
                sample != null, sample, shaped);
        if (decision == WitnessLogic.Decision.PASSED) {
            // Winner. Promote exactly once, then tear down every probe.
            finished = true;
            Object element = firstShapedElement(sample);
            log.info("witness target=urt_emit state=promoted descriptor=" + descriptor
                    + " sample=" + sample.size()
                    + " elementType=" + element.getClass().getName());
            unhookAll();
            mainHandler.removeCallbacksAndMessages(null);
            if (listener != null) {
                listener.onWitnessPromoted(descriptor);
            }
            return;
        }
        if (decision == WitnessLogic.Decision.STRIKE) {
            Strike strike = strikes.get(descriptor);
            if (strike != null && strike.record()) {
                log.info("witness target=urt_emit state=probeDropped descriptor="
                        + descriptor + " reason=shapeStrike");
                unhookOne(descriptor);
            }
        }
    }

    private void expire() {
        if (finished) {
            return;
        }
        finished = true;
        log.info("witness target=urt_emit state=expired reason=timeout"
                + " failOpen=true probes=" + probeHandles.size());
        unhookAll();
        if (listener != null) {
            listener.onWitnessExpired();
        }
    }

    /** Tears everything down without promoting (e.g. a direct path won). */
    void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        unhookAll();
        mainHandler.removeCallbacksAndMessages(null);
        log.info("witness target=urt_emit state=cancelled");
    }

    boolean isFinished() {
        return finished;
    }

    private void unhookOne(String descriptor) {
        HookHandle handle = probeHandles.remove(descriptor);
        strikes.remove(descriptor);
        if (handle != null) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
        if (probeHandles.isEmpty() && !finished) {
            finished = true;
            log.info("witness target=urt_emit state=failed reason=allProbesDropped");
            if (listener != null) {
                listener.onWitnessExpired();
            }
        }
    }

    private void unhookAll() {
        List<HookHandle> handles = new ArrayList<>(probeHandles.values());
        probeHandles.clear();
        strikes.clear();
        for (HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    private Object firstShapedElement(List<?> sample) {
        int limit = Math.min(sample.size(), 8);
        for (int index = 0; index < limit; index++) {
            Object element = sample.get(index);
            if (element == null) {
                continue;
            }
            if (detector.looksLikeModelType(element.getClass())) {
                return element;
            }
            AdDetector.InspectionPlan plan = detector.planOf(element.getClass());
            if (plan != null && plan.entryId != null) {
                try {
                    if (plan.entryId.invoke(element) instanceof String) {
                        return element;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static final class Strike {
        int count;

        boolean record() {
            return ++count >= WitnessLogic.FAILURE_LIMIT;
        }
    }
}
