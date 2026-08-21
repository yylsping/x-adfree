package io.github.yylsping.xadfree;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * Prefixed diagnostics. Every line starts with a component prefix
 * (resolver/cache/candidate/witness/hook/bootstrap/detector) so a single
 * LSPosed log snapshot can be filtered by subsystem.
 *
 * <p>Line content is part of the contract: tests pin exact outcomes (e.g.
 * {@code unhooked=false} when the framework handle throws), so wording
 * changes here are behavior changes.
 */
final class ModuleLog {
    private static final String TAG = "XAdFree";

    /** Injectable line sink for JVM tests. */
    interface Sink {
        void log(int priority, String tag, String message, Throwable throwable);
    }

    private final Sink sink;
    private final boolean verbose;

    ModuleLog(Sink sink, boolean verbose) {
        this.sink = sink;
        this.verbose = verbose;
    }

    /** Production adapter over the libxposed module logger. */
    static ModuleLog forModule(XposedModule module, boolean verbose) {
        return new ModuleLog(module == null ? null : (priority, tag, message, throwable) -> {
            if (throwable != null) {
                module.log(priority, tag, message, throwable);
            } else {
                module.log(priority, tag, message);
            }
        }, verbose);
    }

    /** No-op logger for JVM tests. */
    static ModuleLog silent() {
        return new ModuleLog((Sink) null, false);
    }

    void info(String message) {
        if (sink != null) {
            sink.log(Log.INFO, TAG, message, null);
        }
    }

    void error(String message, Throwable throwable) {
        if (sink != null) {
            sink.log(Log.ERROR, TAG, message, throwable);
        }
    }

    /** Extra detail that would be noisy in normal operation. */
    void debug(String message) {
        if (verbose && sink != null) {
            sink.log(Log.DEBUG, TAG, message, null);
        }
    }
}
