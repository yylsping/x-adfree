package io.github.yylsping.xadfree;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory HookFramework for JVM tests. */
public class FakeHookFramework implements HookFramework {
    /** Result marker returned by proceed() with original arguments. */
    public static final Object ORIGINAL = new Object() {
        @Override
        public String toString() {
            return "<original>";
        }
    };

    public static final class Install {
        public final Method method;
        public final String id;
        final HookCallback callback;
        volatile boolean unhooked;

        Install(Method method, String id, HookCallback callback) {
            this.method = method;
            this.id = id;
            this.callback = callback;
        }

        public boolean isUnhooked() {
            return unhooked;
        }

        /** Fires the interception with the given arguments. */
        public Chain fire(Object... args) {
            Chain chain = new Chain(args);
            try {
                chain.result = callback.intercept(chain);
            } catch (Throwable error) {
                throw new AssertionError("callback threw", error);
            }
            return chain;
        }
    }

    /** Argument-capturing invocation chain. */
    public static final class Chain implements HookInvocation {
        public final Object[] args;
        public final List<Object[]> replacements = new ArrayList<>();
        Object result;

        Chain(Object[] args) {
            this.args = args;
        }

        @Override
        public Object getArg(int index) {
            return args[index];
        }

        @Override
        public int argCount() {
            return args.length;
        }

        @Override
        public Object proceed() {
            return ORIGINAL;
        }

        @Override
        public Object proceed(Object[] replaced) {
            replacements.add(replaced);
            return ORIGINAL;
        }

        public Object result() {
            return result;
        }
    }

    private final List<Install> installs = new CopyOnWriteArrayList<>();

    public List<Install> installs() {
        return installs;
    }

    public Install findByMethod(Method method) {
        for (Install install : installs) {
            if (install.method.equals(method) && !install.unhooked) {
                return install;
            }
        }
        return null;
    }

    public Install findById(String id) {
        for (Install install : installs) {
            if (id.equals(install.id) && !install.unhooked) {
                return install;
            }
        }
        return null;
    }

    public Install findLiveByMethod(Method method) {
        return findByMethod(method);
    }

    @Override
    public HookHandle hook(Method method, String id, HookCallback callback) {
        Install install = new Install(method, id, callback);
        installs.add(install);
        return () -> install.unhooked = true;
    }
}
