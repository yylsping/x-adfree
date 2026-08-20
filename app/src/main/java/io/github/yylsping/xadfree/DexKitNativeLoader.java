package io.github.yylsping.xadfree;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the packaged libdexkit.so into the target app's files dir and
 * loads it by absolute path.
 *
 * <p>The module APK path comes from {@link io.github.libxposed.api.XposedInterface#getModuleApplicationInfo()}
 * (provided by the framework), because the target app's own PackageManager
 * query is subject to Android 11+ package-visibility filtering and usually
 * cannot see the module package at all. The PackageManager lookup is only a
 * fallback for frameworks without that API.
 */
final class DexKitNativeLoader {
    private static final String MODULE_PACKAGE = "io.github.yylsping.xadfree";
    private static volatile boolean loaded;

    /** Framework-provided module info; may return null or throw on old frameworks. */
    interface ModuleInfoSupplier {
        ApplicationInfo get();
    }

    static synchronized void ensureLoaded(Context appContext, ModuleInfoSupplier supplier) {
        if (loaded) {
            return;
        }
        ApplicationInfo moduleInfo = null;
        try {
            if (supplier != null) {
                moduleInfo = supplier.get();
            }
        } catch (Throwable ignored) {
        }
        if (moduleInfo == null || moduleInfo.sourceDir == null) {
            moduleInfo = queryPackageManager(appContext);
        }
        if (moduleInfo == null || moduleInfo.sourceDir == null) {
            throw new IllegalStateException("module apk path unavailable");
        }
        // ApplicationInfo carries no versionCode; the APK byte length is a
        // sufficient staleness stamp for the extracted library name.
        long stamp = new File(moduleInfo.sourceDir).length();
        loadFromApk(appContext, moduleInfo.sourceDir, stamp);
    }

    private static ApplicationInfo queryPackageManager(Context appContext) {
        try {
            return appContext.getPackageManager().getApplicationInfo(MODULE_PACKAGE, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void loadFromApk(Context appContext, String apkPath, long moduleVersion) {
        File library = null;
        try {
            String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            String entryName = "lib/" + abi + "/libdexkit.so";
            library = new File(appContext.getFilesDir(),
                    "libdexkit-" + moduleVersion + ".so");

            if (!library.isFile() || library.length() <= 0) {
                extract(apkPath, entryName, library);
            }
            System.load(library.getAbsolutePath());
            loaded = true;
        } catch (Throwable first) {
            try {
                if (library != null && library.isFile()) {
                    System.load(library.getAbsolutePath());
                    loaded = true;
                    return;
                }
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("unable to load dexkit native library", first);
        }
    }

    private static void extract(String apkPath, String entryName, File output) throws Exception {
        File temp = new File(output.getParentFile(), output.getName() + ".tmp");
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("missing zip entry " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        if (!temp.renameTo(output)) {
            try (InputStream in = new java.io.FileInputStream(temp);
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }
}
