package io.github.yylsping.xadfree;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the packaged libdexkit.so into the target app's files dir and
 * loads it by absolute path.
 *
 * <p>The module APK path comes from {@code XposedInterface.getModuleApplicationInfo()}
 * (provided by the framework), because the target app's own PackageManager
 * query is subject to Android 11+ package-visibility filtering and usually
 * cannot see the module package at all. The PackageManager lookup is only a
 * fallback for frameworks without that API.
 *
 * <p>The extracted-library cache key is content-addressed (P2-4): ABI plus
 * the CRC and uncompressed size of the packaged {@code libdexkit.so} entry.
 * Two module APKs of identical byte length but different native content
 * therefore cannot reuse each other's extraction. Superseded extractions are
 * cleaned up after a successful load; cleanup failure never blocks loading.
 */
final class DexKitNativeLoader {
    private static final String MODULE_PACKAGE = "io.github.yylsping.xadfree";
    private static final String LIBRARY_BASE_NAME = "libdexkit";
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
        loadFromApk(appContext, moduleInfo.sourceDir);
    }

    private static ApplicationInfo queryPackageManager(Context appContext) {
        try {
            return appContext.getPackageManager().getApplicationInfo(MODULE_PACKAGE, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void loadFromApk(Context appContext, String apkPath) {
        File library = null;
        try {
            String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            String entryName = "lib/" + abi + "/libdexkit.so";
            EntryStamp stamp = entryStamp(apkPath, entryName);
            library = new File(appContext.getFilesDir(),
                    LIBRARY_BASE_NAME + "-" + abi + "-" + stamp.cacheKey + ".so");

            if (!library.isFile() || library.length() != stamp.size) {
                extract(apkPath, entryName, library, stamp.size);
            }
            System.load(library.getAbsolutePath());
            loaded = true;
            cleanupSuperseded(appContext, library);
        } catch (Throwable first) {
            try {
                if (library != null && library.isFile()) {
                    System.load(library.getAbsolutePath());
                    loaded = true;
                    cleanupSuperseded(appContext, library);
                    return;
                }
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("unable to load dexkit native library", first);
        }
    }

    private static final class EntryStamp {
        final String cacheKey;
        final long size;

        EntryStamp(String cacheKey, long size) {
            this.cacheKey = cacheKey;
            this.size = size;
        }
    }

    private static EntryStamp entryStamp(String apkPath, String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("missing zip entry " + entryName);
            }
            return new EntryStamp(
                    Long.toHexString(entry.getCrc()) + "-" + entry.getSize(),
                    entry.getSize());
        }
    }

    private static void extract(String apkPath, String entryName, File output, long expectedSize)
            throws Exception {
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
                out.getFD().sync();
            }
        }
        if (expectedSize > 0 && temp.length() != expectedSize) {
            throw new IllegalStateException("extracted size mismatch");
        }
        try {
            Files.move(temp.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable atomicUnsupported) {
            Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes other libdexkit-*.so extractions; best-effort only. */
    private static void cleanupSuperseded(Context appContext, File keep) {
        try {
            File[] siblings = appContext.getFilesDir()
                    .listFiles((dir, name) ->
                            name.startsWith(LIBRARY_BASE_NAME + "-") && name.endsWith(".so"));
            if (siblings == null) {
                return;
            }
            for (File sibling : siblings) {
                if (!sibling.equals(keep)) {
                    //noinspection ResultOfMethodCallIgnored
                    sibling.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
