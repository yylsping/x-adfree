package io.github.yylsping.xadfree;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflection re-verification of cached or DexKit candidates before any hook
 * is installed. Structural checks only — business semantics are proven by the
 * runtime witness.
 */
final class XTargetVerifier {
    static final String CONTINUATION_CLASS = "kotlin.coroutines.Continuation";
    static final String FLOW_COLLECTOR_CLASS = "kotlinx.coroutines.flow.h";

    enum Verdict {
        VALIDATED_STATIC,
        NEEDS_RUNTIME_WITNESS,
        INVALID
    }

    static final class Verification {
        final Verdict verdict;
        final String reason;

        Verification(Verdict verdict, String reason) {
            this.verdict = verdict;
            this.reason = reason;
        }
    }

    private XTargetVerifier() {
    }

    /**
     * Verifies one urt_emit target: class loadable, method loadable, exact
     * (Object, Continuation) -> Object CPS shape, non-abstract instance
     * method, declaring class implements FlowCollector, and still owned by
     * the target app ClassLoader.
     */
    static Verification verifyUrtEmit(ResolvedTarget target, ClassLoader loader,
                                      Class<?> objectType, Class<?> continuationType) {
        String rejected = rejectUrtEmit(target, loader, objectType, continuationType);
        if (rejected != null) {
            return new Verification(Verdict.INVALID, rejected);
        }
        // Structural proof is complete; business proof still needs the first
        // real invocation unless a previous session already witnessed it.
        return new Verification(
                target.runtimeWitnessed ? Verdict.VALIDATED_STATIC : Verdict.NEEDS_RUNTIME_WITNESS,
                "shapeOk witnessed=" + target.runtimeWitnessed);
    }

    static String rejectUrtEmit(ResolvedTarget target, ClassLoader loader,
                                Class<?> objectType, Class<?> continuationType) {
        if (target == null) {
            return "null target";
        }
        if (target.methodDescriptor == null || target.methodDescriptor.isEmpty()) {
            return "empty method descriptor";
        }
        try {
            Class<?> type = DescriptorUtils.classForName(target.classDescriptor, loader);
            if (type == null) {
                return "class not loadable";
            }
            Method method = DescriptorUtils.methodForDescriptor(target.methodDescriptor, loader);
            if (method == null) {
                return "method not loadable";
            }
            if (Modifier.isStatic(method.getModifiers())) {
                return "static method";
            }
            if (Modifier.isAbstract(method.getModifiers())) {
                return "abstract method";
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[0] != objectType || params[1] != continuationType) {
                return "parameter shape mismatch";
            }
            if (method.getReturnType() != objectType) {
                return "return type mismatch";
            }
            if (!declaresFlowCollector(type, loader)) {
                return "declaring class does not implement FlowCollector";
            }
            if (!isOwnedByLoader(type, loader)) {
                return "class not owned by target loader";
            }
            return null;
        } catch (Throwable throwable) {
            return String.valueOf(throwable);
        }
    }

    /** Verifies the optional model interface target. */
    static String rejectModelInterface(ResolvedTarget target, ClassLoader loader) {
        if (target == null) {
            return "null target";
        }
        try {
            Class<?> type = DescriptorUtils.classForName(target.classDescriptor, loader);
            if (type == null) {
                return "class not loadable";
            }
            if (!type.isInterface()) {
                return "not an interface";
            }
            Method entryId;
            try {
                entryId = type.getMethod("getEntryId");
            } catch (NoSuchMethodException ignored) {
                return "missing getEntryId";
            }
            if (entryId.getReturnType() != String.class) {
                return "getEntryId does not return String";
            }
            try {
                type.getMethod("getSortIndex");
            } catch (NoSuchMethodException ignored) {
                return "missing getSortIndex";
            }
            return null;
        } catch (Throwable throwable) {
            return String.valueOf(throwable);
        }
    }

    /** Verifies the optional app isAd helper: static, (modelInterface) -> boolean. */
    static String rejectAdHelper(ResolvedTarget target, ClassLoader loader,
                                 Class<?> modelInterface) {
        if (target == null) {
            return "null target";
        }
        try {
            Method method = DescriptorUtils.methodForDescriptor(target.methodDescriptor, loader);
            if (method == null) {
                return "method not loadable";
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                return "not static";
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].isAssignableFrom(modelInterface)) {
                return "parameter shape mismatch";
            }
            if (method.getReturnType() != boolean.class) {
                return "not boolean";
            }
            return null;
        } catch (Throwable throwable) {
            return String.valueOf(throwable);
        }
    }

    static boolean declaresFlowCollector(Class<?> type, ClassLoader loader) {
        try {
            Class<?> collector = Class.forName(FLOW_COLLECTOR_CLASS, false, loader);
            return collector.isAssignableFrom(type);
        } catch (Throwable ignored) {
            // FlowCollector not loadable (kotlinx missing): treat the check as
            // unavailable rather than rejecting every candidate.
            return true;
        }
    }

    static boolean isOwnedByLoader(Class<?> type, ClassLoader loader) {
        ClassLoader cursor = type.getClassLoader();
        while (cursor != null) {
            if (cursor == loader) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }
}
