package io.github.yylsping.xadfree;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Installs the URT data-layer filter hook and performs the in-hook runtime
 * witness: the first real invocations must deliver a List whose elements
 * look like URT entries (model interface or an entryId accessor). A hook
 * whose invocations keep failing the shape check unhooks itself, so a
 * mis-fingerprinted candidate can never filter unrelated flows.
 */
final class UrtEmitHooks {
    interface Listener {
        void onWitnessPassed(String methodDescriptor);
        void onWitnessFailed(String methodDescriptor, String reason);
    }

    private final XposedModule module;
    private final ModuleLog log;
    private final AdDetector detector;
    private final UrtListFilter filter;
    private final Listener listener;

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong removals = new AtomicLong();
    private volatile boolean removalLogged;

    UrtEmitHooks(XposedModule module, ModuleLog log, AdDetector detector,
                 UrtListFilter filter, Listener listener) {
        this.module = module;
        this.log = log;
        this.detector = detector;
        this.filter = filter;
        this.listener = listener;
    }

    /** Installs the permanent filter hook; the witness validates it in place. */
    HookHandle install(Method emitMethod) {
        final String descriptor = UrtEmitResolver.dexDescriptorOf(emitMethod);
        final WitnessState witness = new WitnessState(descriptor);
        try {
            HookHandle handle = module.hook(emitMethod)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("xadfree-urt-emit-filter")
                    .intercept(chain -> {
                        Object argument = chain.getArg(0);
                        if (!(argument instanceof List<?>)) {
                            witness.failure("argNotList");
                            return chain.proceed();
                        }
                        List<?> incoming = (List<?>) argument;
                        if (!witness.sample(incoming)) {
                            return chain.proceed();
                        }
                        try {
                            List<?> filtered = filter.filter(incoming);
                            long removed = incoming.size() - filtered.size();
                            if (filtered != incoming) {
                                calls.incrementAndGet();
                                removals.addAndGet(removed);
                                logRemoval(descriptor, incoming.size(), removed);
                                return chain.proceed(new Object[]{filtered, chain.getArg(1)});
                            }
                        } catch (Throwable throwable) {
                            log.error("hook target=urt_emit filtering failed;"
                                    + " preserving original list", throwable);
                        }
                        return chain.proceed();
                    });
            log.info("hook target=urt_emit installed=true descriptor=" + descriptor
                    + " runtimeWitness=pending");
            return handle;
        } catch (Throwable throwable) {
            log.error("hook target=urt_emit install failed descriptor=" + descriptor, throwable);
            return null;
        }
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
                    if (listener != null) {
                        listener.onWitnessFailed(descriptor, "shapeMismatchLimit");
                    }
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
                if (listener != null) {
                    listener.onWitnessFailed(descriptor, reason);
                }
            }
        }

        private Object firstShapedElement(List<?> incoming) {
            int limit = Math.min(incoming.size(), 8);
            for (int index = 0; index < limit; index++) {
                Object element = incoming.get(index);
                if (element == null) {
                    continue;
                }
                if (detector.looksLikeModelType(element.getClass())) {
                    return element;
                }
                AdDetector.InspectionPlan plan = planOf(element.getClass());
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

        private AdDetector.InspectionPlan planOf(Class<?> type) {
            try {
                return detector.planOf(type);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
