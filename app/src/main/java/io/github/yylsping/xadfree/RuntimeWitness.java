package io.github.yylsping.xadfree;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Short-lived probe witness for ambiguous static resolutions: DexKit narrowed
 * the field but the top scores are too close (or too weak) to pick a winner.
 *
 * <p>Up to {@link #MAX_PROBES} candidates receive a temporary observe-only
 * hook (no filtering, no argument mutation). Confirmation is evidence-based
 * (P1-8): a candidate must be seen on at least
 * {@link WitnessLogic#MIN_CONFIRM_INVOCATIONS} non-empty invocations whose
 * inspected elements are predominantly URT-entry shaped; a single weak sample
 * never promotes. The first confirmed candidate wins; every probe is then
 * unhooked. A bounded lifetime timer expires the whole set fail-open.
 *
 * <p>Callbacks arrive on hooked threads; all mutable state is guarded by
 * {@code lock} and listeners are invoked outside it. Termination is
 * exactly-once through {@link #settled}.
 */
final class RuntimeWitness {
    static final int MAX_PROBES = 5;
    static final long PROBE_TIMEOUT_MILLIS = 30_000L;

    /** Observable per-candidate lifecycle (P1-8 witness states). */
    enum WitnessState { OBSERVING, LIKELY, CONFIRMED, REJECTED, TIMED_OUT }

    interface Listener {
        void onWitnessPromoted(String methodDescriptor, String evidenceSummary);

        /** Probes ended without a confirmed candidate: timeout or all rejected. */
        void onWitnessExpired(String reason);
    }

    private final HookFramework framework;
    private final ModuleLog log;
    private final AdDetector detector;
    private final Listener listener;
    private final Scheduler scheduler;

    private final Object lock = new Object();
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    private final AtomicBoolean settled = new AtomicBoolean();
    private String settleReason;

    RuntimeWitness(HookFramework framework, ModuleLog log, AdDetector detector,
                   Listener listener, Scheduler scheduler) {
        this.framework = framework;
        this.log = log;
        this.detector = detector;
        this.listener = listener;
        this.scheduler = scheduler;
    }

    /** Installs observe-only probes on the top ambiguous candidates. */
    boolean installProbes(List<Method> methods) {
        if (settled.get() || methods == null || methods.isEmpty()) {
            return false;
        }
        List<Probe> installed = new ArrayList<>();
        synchronized (lock) {
            for (Method method : methods) {
                if (installed.size() >= MAX_PROBES) {
                    break;
                }
                String descriptor = UrtEmitResolver.dexDescriptorOf(method);
                if (probes.containsKey(descriptor)) {
                    continue;
                }
                HookFramework.HookHandle handle = framework.hook(
                        method, "xadfree-urt-emit-probe",
                        invocation -> {
                            observe(descriptor, invocation.getArg(0));
                            return invocation.proceed();
                        });
                if (handle == null) {
                    log.error("witness target=urt_emit probeInstallFailed descriptor="
                            + descriptor, null);
                    continue;
                }
                Probe probe = new Probe(descriptor, handle);
                probes.put(descriptor, probe);
                installed.add(probe);
                log.info("witness target=urt_emit state=probeInstalled descriptor="
                        + descriptor);
            }
        }
        if (installed.isEmpty()) {
            return false;
        }
        log.info("witness target=urt_emit state=probing probes=" + installed.size()
                + " minInvocations=" + WitnessLogic.MIN_CONFIRM_INVOCATIONS
                + " minShapedRatio=" + WitnessLogic.MIN_SHAPED_RATIO
                + " timeoutMs=" + PROBE_TIMEOUT_MILLIS);
        scheduler.postDelayed("witness-timeout", this::expire, PROBE_TIMEOUT_MILLIS);
        return true;
    }

    /** Observes one invocation of one probed candidate (any thread). */
    void observe(String descriptor, Object argument) {
        if (settled.get()) {
            return;
        }
        List<?> sample = argument instanceof List<?> ? (List<?>) argument : null;
        int sampled = 0;
        int shaped = 0;
        boolean adEvidence = false;
        if (sample != null && !sample.isEmpty()) {
            int cap = WitnessLogic.ProbeEvidence.sampleCap(sample.size());
            for (int index = 0; index < cap; index++) {
                Object element = sample.get(index);
                if (element == null) {
                    sampled++;
                    continue;
                }
                sampled++;
                if (isShaped(element)) {
                    shaped++;
                    if (!adEvidence && detector.detect(element).verdict == AdDetector.Verdict.AD) {
                        adEvidence = true;
                    }
                }
            }
        }

        String promote = null;
        String drop = null;
        synchronized (lock) {
            if (settled.get()) {
                return;
            }
            Probe probe = probes.get(descriptor);
            if (probe == null) {
                return;
            }
            probe.evidence.record(sample != null, sample, sampled, shaped, adEvidence);
            if (probe.evidence.confirmed()) {
                probe.state = WitnessState.CONFIRMED;
                promote = descriptor;
            } else if (probe.evidence.likely() && probe.state == WitnessState.OBSERVING) {
                probe.state = WitnessState.LIKELY;
                log.info("witness target=urt_emit state=likely descriptor=" + descriptor
                        + " " + probe.evidence.describe());
            } else if (probe.evidence.strikeLimitReached()) {
                probe.state = WitnessState.REJECTED;
                drop = descriptor;
            }
        }

        if (promote != null) {
            settlePromoted(promote);
            return;
        }
        if (drop != null) {
            dropProbe(drop, "shapeStrikeLimit");
        }
    }

    private void settlePromoted(String descriptor) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        String summary;
        synchronized (lock) {
            settleReason = "promoted";
            Probe probe = probes.get(descriptor);
            summary = probe == null ? "" : probe.evidence.describe();
            unhookAllLocked();
        }
        scheduler.cancel("witness-timeout");
        log.info("witness target=urt_emit state=promoted descriptor=" + descriptor
                + " " + summary);
        if (listener != null) {
            listener.onWitnessPromoted(descriptor, summary);
        }
    }

    private void dropProbe(String descriptor, String reason) {
        boolean allDropped = false;
        synchronized (lock) {
            Probe probe = probes.remove(descriptor);
            if (probe != null) {
                try {
                    probe.handle.unhook();
                } catch (Throwable ignored) {
                }
            }
            log.info("witness target=urt_emit state=rejected descriptor=" + descriptor
                    + " reason=" + reason);
            // Rejected probes are removed immediately; an empty map means no
            // observable candidate remains.
            allDropped = probes.isEmpty();
            if (allDropped) {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                settleReason = "allProbesRejected";
                unhookAllLocked();
            }
        }
        if (allDropped) {
            scheduler.cancel("witness-timeout");
            log.info("witness target=urt_emit state=expired reason=allProbesRejected"
                    + " failOpen=true");
            if (listener != null) {
                listener.onWitnessExpired("allProbesRejected");
            }
        }
    }

    private void expire() {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            settleReason = "timeout";
            unhookAllLocked();
        }
        scheduler.cancel("witness-timeout");
        log.info("witness target=urt_emit state=expired reason=timeout failOpen=true");
        if (listener != null) {
            listener.onWitnessExpired("timeout");
        }
    }

    /** Tears everything down without promoting (e.g. coordinator gave up). */
    void cancel() {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            settleReason = "cancelled";
            unhookAllLocked();
        }
        scheduler.cancel("witness-timeout");
        log.info("witness target=urt_emit state=cancelled");
    }

    boolean isSettled() {
        return settled.get();
    }

    String settleReason() {
        synchronized (lock) {
            return settleReason;
        }
    }

    private void unhookAllLocked() {
        List<Probe> remaining = new ArrayList<>(probes.values());
        probes.clear();
        for (Probe probe : remaining) {
            try {
                probe.handle.unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isShaped(Object element) {
        if (detector.looksLikeModelType(element.getClass())) {
            return true;
        }
        AdDetector.InspectionPlan plan = detector.planOf(element.getClass());
        if (plan != null && plan.entryId != null) {
            try {
                return plan.entryId.invoke(element) instanceof String;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static final class Probe {
        final String descriptor;
        final HookFramework.HookHandle handle;
        final WitnessLogic.ProbeEvidence evidence = new WitnessLogic.ProbeEvidence();
        WitnessState state = WitnessState.OBSERVING;

        Probe(String descriptor, HookFramework.HookHandle handle) {
            this.descriptor = descriptor;
            this.handle = handle;
        }
    }
}
