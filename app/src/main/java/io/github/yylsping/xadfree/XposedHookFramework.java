package io.github.yylsping.xadfree;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Production {@link HookFramework} over libxposed Modern API 102. Every
 * interception is exception-protected twice: the framework's PROTECTIVE mode,
 * plus a local catch-all that re-runs the original call, so a module bug can
 * never crash the target app.
 */
final class XposedHookFramework implements HookFramework {
    private final io.github.libxposed.api.XposedModule module;

    XposedHookFramework(io.github.libxposed.api.XposedModule module) {
        this.module = module;
    }

    @Override
    public HookHandle hook(Method method, String id, HookCallback callback) {
        try {
            io.github.libxposed.api.XposedInterface.HookHandle handle = module.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(id)
                    .intercept((Hooker) chain -> {
                        try {
                            return callback.intercept(new XposedInvocation(chain));
                        } catch (OriginalCallError original) {
                            // The hooked method itself threw: rethrow as-is.
                            // Re-running proceed() here would execute the
                            // original method twice.
                            throw rethrow(original.getCause());
                        } catch (Throwable moduleError) {
                            // Module bug: last-resort fail-open before
                            // PROTECTIVE sees it.
                            try {
                                return chain.proceed();
                            } catch (Throwable original) {
                                throw rethrow(original);
                            }
                        }
                    });
            return handle == null ? null : handle::unhook;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** Marker distinguishing original-method failures from module bugs. */
    private static final class OriginalCallError extends RuntimeException {
        OriginalCallError(Throwable cause) {
            super(cause);
        }
    }

    private static final class XposedInvocation implements HookInvocation {
        private final Chain chain;

        XposedInvocation(Chain chain) {
            this.chain = chain;
        }

        @Override
        public Object getArg(int index) {
            return chain.getArg(index);
        }

        @Override
        public int argCount() {
            List<Object> args = chain.getArgs();
            return args == null ? 0 : args.size();
        }

        @Override
        public Object proceed() {
            try {
                return chain.proceed();
            } catch (Throwable error) {
                throw new OriginalCallError(error);
            }
        }

        @Override
        public Object proceed(Object[] args) {
            try {
                return chain.proceed(args);
            } catch (Throwable error) {
                throw new OriginalCallError(error);
            }
        }
    }

    /** Sneaky-rethrow so the original exception identity survives the seam. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable error) throws T {
        throw (T) error;
    }
}
