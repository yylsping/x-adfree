package io.github.yylsping.xadfree;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Installs the URT data-layer filter hook and performs the in-hook runtime
 * witness: the first real invocation must deliver a List whose elements look
 * like URT entries (model interface or an entryId accessor). A hook whose
 * invocations keep failing the shape check is <b>really</b> unhooked (P1-3),
 * its registry entry removed and the coordinator notified, so a
 * mis-fingerprinted candidate can never keep an interceptor in the process.
 *
 * <p>Hot-path discipline (P2-2/P2-3): the interceptor only detects, filters
 * and counts — no disk IO, no JSON, no logging of content. Cache persistence
 * happens on the coordinator worker via the listener callbacks. Replacement
 * lists are plain {@code ArrayList}; any other incoming List implementation
 * is passed through unfiltered (fail-open) and its class name logged once.
 */
final class UrtEmitHooks {
    interface Listener {
        void onWitnessPassed(String methodDescriptor);

        void onWitnessFailed(String methodDescriptor, String reason);
    }

    private final HookFramework framework;
    private final ModuleLog log;
    private final AdDetector detector;
    private final UrtListFilter filter;
    private final Listener listener;

    /** descriptor → installed hook; real unhook + removal on witness failure. */
    private final ConcurrentHashMap<String, InstalledHook> installed = new ConcurrentHashMap<>();
    private final Set<String> seenForeignListClasses = ConcurrentHashMap.newKeySet();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong removals = new AtomicLong();
    private volatile boolean removalLogged;

    UrtEmitHooks(HookFramework framework, ModuleLog log, AdDetector detector,
                 UrtListFilter filter, Listener listener) {
        this.framework = framework;
        this.log = log;
        this.detector = detector;
        this.filter = filter;
        this.listener = listener;
    }

    /** Installs the permanent filter hook; the witness validates it in place. */
    boolean install(Method emitMethod) {
        final String descriptor = UrtEmitResolver.dexDescriptorOf(emitMethod);
        if (installed.containsKey(descriptor)) {
            return true;
        }
        final WitnessState witness = new WitnessState(descriptor);
        try {
            HookFramework.HookHandle handle = framework.hook(
                    emitMethod, "xadfree-urt-emit-filter",
                    invocation -> intercept(descriptor, witness, invocation));
            if (handle == null) {
                return false;
            }
            installed.put(descriptor, new InstalledHook(handle, emitMethod));
            log.info("hook target=urt_emit installed=true descriptor=" + descriptor
                    + " runtimeWitness=pending");
            return true;
        } catch (Throwable throwable) {
            log.error("hook target=urt_emit install failed descriptor=" + descriptor, throwable);
            return false;
        }
    }

    /** True unhook of one installed filter hook (coordinator worker thread). */
    boolean unhook(String descriptor) {
        InstalledHook hook = installed.remove(descriptor);
        if (hook == null) {
            return false;
        }
        try {
            hook.handle.unhook();
        } catch (Throwable ignored) {
        }
        log.info("hook target=urt_emit unhooked=true descriptor=" + descriptor);
        return true;
    }

    boolean hasInstalled() {
        return !installed.isEmpty();
    }

    int installedCount() {
        return installed.size();
    }

    Method methodOf(String descriptor) {
        InstalledHook hook = installed.get(descriptor);
        return hook == null ? null : hook.method;
    }

    List<String> installedDescriptors() {
        return new ArrayList<>(installed.keySet());
    }

    private Object intercept(String descriptor, WitnessState witness,
                             HookFramework.HookInvocation invocation) {
        Object argument = invocation.getArg(0);
        if (!(argument instanceof List<?>)) {
            witness.failure("argNotList");
            return invocation.proceed();
        }
        List<?> incoming = (List<?>) argument;
        if (!isReplaceableList(incoming)) {
            return invocation.proceed();
        }
        if (!witness.sample(incoming)) {
            return invocation.proceed();
        }
        try {
            List<?> filtered = filter.filter(incoming);
            if (filtered != incoming) {
                long removed = incoming.size() - filtered.size();
                calls.incrementAndGet();
                removals.addAndGet(removed);
                logRemoval(descriptor, incoming.size(), removed);
                return invocation.proceed(new Object[]{filtered, invocation.getArg(1)});
            }
        } catch (Throwable throwable) {
            log.error("hook target=urt_emit filtering failed;"
                    + " preserving original list", throwable);
        }
        return invocation.proceed();
    }

    /**
     * Replacement policy (P2-3): filtering replaces the incoming list with a
     * plain ArrayList. That is verified-safe for the ArrayList outputs the
     * URT map step actually produces; unknown List implementations could
     * carry downstream behavioral assumptions, so they pass through unfiltered
     * (fail-open) and are logged by class name once.
     */
    private boolean isReplaceableList(List<?> incoming) {
        if (incoming instanceof ArrayList) {
            return true;
        }
        String className = incoming.getClass().getName();
        if (seenForeignListClasses.add(className) && seenForeignListClasses.size() <= 8) {
            log.info("hook target=urt_emit listPolicy=foreignFailOpen class=" + className);
        }
        return false;
    }

    private void logRemoval(String descriptor, int incoming, long removed) {
        if (removalLogged && removals.get() % 50L != 0L) {
            return;
        }
        removalLogged = true;
        log.info("hook target=urt_emit filtered=true descriptor=" + descriptor
                + " incoming=" + incoming + " removed=" + removed
                + " totals calls=" + calls + " removedSum=" + removals
                + " detector inspected=" + detector.inspectedCount()
                + " ads=" + detector.adCount()
                + " plans=" + detector.cachedPlanCount());
    }

    /**
     * Bounded in-hook witness. The first non-empty sample must contain at
     * least one element with a URT entry shape; empty lists carry no evidence
     * either way. Fail-open on every call: a not-yet-witnessed or failed hook
     * still passes content through untouched.
     */
    private final class WitnessState {
        private final String descriptor;
        private int failures;
        private boolean passed;
        private boolean dead;

        WitnessState(String descriptor) {
            this.descriptor = descriptor;
        }

        /** @return true when filtering is allowed for this invocation. */
        boolean sample(List<?> incoming) {
            if (dead) {
                return false;
            }
            if (passed) {
                return true;
            }
            Object evidenceElement = firstShapedElement(incoming);
            WitnessLogic.Decision decision = WitnessLogic.onInvocation(
                    true, incoming, evidenceElement != null);
            if (decision == WitnessLogic.Decision.PASSED) {
                passed = true;
                log.info("witness target=urt_emit state=passed descriptor=" + descriptor
                        + " sample=" + incoming.size()
                        + " elementType=" + evidenceElement.getClass().getName());
                if (listener != null) {
                    listener.onWitnessPassed(descriptor);
                }
                return true;
            }
            if (decision == WitnessLogic.Decision.STRIKE) {
                failures++;
                log.info("witness target=urt_emit state=mismatch descriptor=" + descriptor
                        + " sample=" + incoming.size()
                        + " element=" + elementDescription(incoming)
                        + " failures=" + failures);
                if (WitnessLogic.limitReached(failures)) {
                    dead = true;
                    log.info("witness target=urt_emit state=failed descriptor=" + descriptor
                            + " reason=shapeMismatchLimit unhooked=true");
                    unhookByWitness(descriptor, "shapeMismatchLimit");
                }
            }
            // Not yet witnessed: fail-open, no filtering.
            return false;
        }

        void failure(String reason) {
            if (dead) {
                return;
            }
            failures++;
            if (WitnessLogic.limitReached(failures)) {
                dead = true;
                log.info("witness target=urt_emit state=failed descriptor=" + descriptor
                        + " reason=" + reason + " unhooked=true");
                unhookByWitness(descriptor, reason);
            }
        }

        /** True unhook (P1-3): registry removal, handle teardown, then notify. */
        private void unhookByWitness(String descriptor, String reason) {
            unhook(descriptor);
            if (listener != null) {
                listener.onWitnessFailed(descriptor, reason);
            }
        }

        private Object firstShapedElement(List<?> incoming) {
            int limit = WitnessLogic.ProbeEvidence.sampleCap(incoming.size());
            for (int index = 0; index < limit; index++) {
                Object element = incoming.get(index);
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

        private String elementDescription(List<?> incoming) {
            for (Object element : incoming) {
                if (element != null) {
                    return element.getClass().getName();
                }
            }
            return "empty";
        }
    }

    private static final class InstalledHook {
        final HookFramework.HookHandle handle;
        final Method method;

        InstalledHook(HookFramework.HookHandle handle, Method method) {
            this.handle = handle;
            this.method = method;
        }
    }
}
