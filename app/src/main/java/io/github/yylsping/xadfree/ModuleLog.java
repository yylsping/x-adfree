package io.github.yylsping.xadfree;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * Prefixed diagnostics. Every line starts with a component prefix
 * (resolver/cache/candidate/witness/hook/bootstrap/detector) so a single
 * LSPosed log snapshot can be filtered by subsystem.
 */
final class ModuleLog {
    private static final String TAG = "XAdFree";

    private final XposedModule module;
    private final boolean verbose;

    ModuleLog(XposedModule module, boolean verbose) {
        this.module = module;
        this.verbose = verbose;
    }

    void info(String message) {
        if (module != null) {
            module.log(Log.INFO, TAG, message);
        }
    }

    void error(String message, Throwable throwable) {
        if (module != null) {
            module.log(Log.ERROR, TAG, message, throwable);
        }
    }

    /** Extra detail that would be noisy in normal operation. */
    void debug(String message) {
        if (verbose && module != null) {
            module.log(Log.DEBUG, TAG, message);
        }
    }
}
