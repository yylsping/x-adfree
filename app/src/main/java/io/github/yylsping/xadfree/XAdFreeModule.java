package io.github.yylsping.xadfree;

import io.github.libxposed.api.XposedModule;

/**
 * Modern Xposed API 102 entry.
 *
 * <p>Version-independent by design: no target class, method, field or
 * resource id is hard-coded as a hook. Every hook target is resolved at
 * runtime per installed-APK identity (DexKit semantic fingerprints → runtime
 * witness → per-identity descriptor cache), so ordinary minor updates of X
 * with fresh R8 obfuscation re-resolve automatically instead of breaking.
 */
public final class XAdFreeModule extends XposedModule {
    static final String TARGET_PACKAGE = "com.twitter.android";

    private boolean targetProcess;
    private boolean started;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        targetProcess = TARGET_PACKAGE.equals(param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!targetProcess || !TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        synchronized (this) {
            if (started) {
                return;
            }
            started = true;
        }

        ModuleLog log = ModuleLog.forModule(this, false);
        log.info("bootstrap moduleLoaded target=" + TARGET_PACKAGE
                + " mode=dexkit-dynamic-resolution");
        try {
            HookCoordinator.forProduction(this, log, TARGET_PACKAGE, param.getClassLoader())
                    .install();
        } catch (Throwable error) {
            log.error("bootstrap coordinator install failed", error);
        }
    }
}
