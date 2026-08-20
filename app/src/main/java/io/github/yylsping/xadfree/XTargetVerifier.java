package io.github.yylsping.xadfree;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/**
 * Reflection re-verification of cached or DexKit candidates before any hook
 * is installed. Structural checks only — business semantics are proven by the
 * runtime witness.
 *
 * <p>Every structural check that can be unavailable is tri-state (P1-2):
 * YES adds evidence, NO rejects or penalizes, UNKNOWN neither credits nor
 * validates. An unverifiable check must never be treated as passed.
 */
final class XTargetVerifier {
    static final String CONTINUATION_CLASS = "kotlin.coroutines.Continuation";

    /** Tri-state result of a structural check that may be unavailable. */
    enum TriState { YES, NO, UNKNOWN }

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
     * method that overrides a matching interface declaration somewhere in its
     * hierarchy, and still owned by the target app ClassLoader.
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
            TriState override = interfaceOverrideShape(method);
            if (override == TriState.NO) {
                return "no interface override of emit shape";
            }
            if (!isOwnedByLoader(type, loader)) {
                return "class not owned by target loader";
            }
            return null;
        } catch (Throwable throwable) {
            return String.valueOf(throwable);
        }
    }

    /**
     * Whether {@code method} overrides a same-name (Object, Continuation) ->
     * Object declaration on some interface of its hierarchy. This is the
     * FlowCollector structural footprint without hard-coding any R8-renamed
     * class (P1-2). YES: proven override; NO: the full hierarchy walked to
     * {@code java.lang.Object} without one; UNKNOWN: hierarchy reflection
     * failed — no credit, no rejection.
     */
    static TriState interfaceOverrideShape(Method method) {
        if (method == null) {
            return TriState.UNKNOWN;
        }
        try {
            Class<?> objectType = Object.class;
            IdentityHashMap<Class<?>, Boolean> seenInterfaces = new IdentityHashMap<>();
            for (Class<?> cursor = method.getDeclaringClass(); cursor != null;
                 cursor = cursor.getSuperclass()) {
                for (Class<?> iface : collectInterfaces(cursor, seenInterfaces)) {
                    for (Method declared : iface.getDeclaredMethods()) {
                        if (Modifier.isStatic(declared.getModifiers())) {
                            continue;
                        }
                        if (!declared.getName().equals(method.getName())) {
                            continue;
                        }
                        Class<?>[] params = declared.getParameterTypes();
                        if (params.length == 2
                                && params[0] == objectType
                                && declared.getReturnType() == objectType
                                && isContinuationParam(params[1])) {
                            return TriState.YES;
                        }
                    }
                }
            }
            return TriState.NO;
        } catch (Throwable throwable) {
            return TriState.UNKNOWN;
        }
    }

    private static boolean isContinuationParam(Class<?> param) {
        // Structural Continuation footprint: any interface declaring
        // getContext()Lkotlin/coroutines/CoroutineContext; — avoids importing
        // the Kotlin runtime on the verifier classpath.
        try {
            return param.isInterface() && param.getMethod("getContext") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static java.util.List<Class<?>> collectInterfaces(
            Class<?> type, IdentityHashMap<Class<?>, Boolean> seen) {
        java.util.ArrayList<Class<?>> found = new java.util.ArrayList<>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        for (Class<?> direct : type.getInterfaces()) {
            queue.add(direct);
        }
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            if (iface == null || seen.put(iface, Boolean.TRUE) != null) {
                continue;
            }
            found.add(iface);
            for (Class<?> parent : iface.getInterfaces()) {
                queue.add(parent);
            }
        }
        return found;
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
        if (modelInterface == null) {
            return "model interface unavailable";
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
