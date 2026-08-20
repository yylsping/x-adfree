package io.github.yylsping.xadfree;

import java.lang.reflect.Method;

/**
 * Minimal hook-framework seam. Production code adapts libxposed Modern API
 * {@code XposedModule.hook(...)} behind it; JVM tests drive a fake so the
 * coordinator state machine, witness lifecycle and hook registry can be
 * exercised without a device.
 *
 * <p>Callbacks run on whatever thread the hooked method executes on — never
 * assume the main thread. Implementations must treat every callback as
 * fail-open: an exception thrown by {@link HookCallback} must not propagate
 * into the target app.
 */
interface HookFramework {

    /** One live interception; mirrors the libxposed hook chain subset in use. */
    interface HookInvocation {
        Object getArg(int index);

        int argCount();

        /** Continues the original method with its original arguments. */
        Object proceed();

        /** Continues the original method with a full argument replacement. */
        Object proceed(Object[] args);
    }

    /** Installed interception handle; {@link #unhook()} is idempotent. */
    interface HookHandle {
        void unhook();
    }

    @FunctionalInterface
    interface HookCallback {
        Object intercept(HookInvocation invocation) throws Throwable;
    }

    /**
     * Installs an interception and returns its handle, or null when the
     * framework refused the hook (never throws for routine failures).
     */
    HookHandle hook(Method method, String id, HookCallback callback);
}
