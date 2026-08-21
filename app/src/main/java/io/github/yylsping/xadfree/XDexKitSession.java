package io.github.yylsping.xadfree;

import android.content.Context;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;

/**
 * DexKitBridge lifecycle. The bridge is bound to the runtime ClassLoader it
 * was created from and rebuilt a bounded number of times at most. X ships all
 * of its DEX inside the base APK and config splits, so no runtime DEX
 * observation is required; a rebuild only happens if a session must retry
 * after a bridge failure.
 */
final class XDexKitSession {
    private static final int MAX_REBUILDS = 3;
    private static final int THREAD_NUM = 2;

    private final ModuleLog log;
    private final Context appContext;
    private final ClassLoader loader;
    private final DexKitNativeLoader.ModuleInfoSupplier moduleInfoSupplier;
    private final Object lock = new Object();

    private DexKitBridge bridge;
    private volatile boolean bridgeClosed;
    private int rebuildCount;
    private long loaderIdentity = -1L;
    private long createdElapsedMs = -1L;
    private int createdDexNum = -1;

    XDexKitSession(ModuleLog log, Context appContext, ClassLoader loader,
                   DexKitNativeLoader.ModuleInfoSupplier moduleInfoSupplier) {
        this.log = log;
        this.appContext = appContext;
        this.loader = loader;
        this.moduleInfoSupplier = moduleInfoSupplier;
    }

    long getLoaderIdentity() {
        return System.identityHashCode(loader);
    }

    /** Returns a valid bridge or null. Never returns an invalid cached bridge. */
    DexKitBridge ensureBridge(String trigger) {
        synchronized (lock) {
            long loaderId = System.identityHashCode(loader);
            if (bridge != null && bridge.isValid() && loaderIdentity == loaderId) {
                return bridge;
            }
            bridgeClosed = false;
            if (rebuildCount >= MAX_REBUILDS) {
                log.info("resolver dexkit rebuild refused rebuildCount=" + rebuildCount
                        + " trigger=" + trigger);
                return null;
            }
            closeBridge();
            rebuildCount++;
            loaderIdentity = loaderId;
            long start = SystemClock.elapsedRealtime();
            try {
                DexKitNativeLoader.ensureLoaded(appContext, moduleInfoSupplier);
                bridge = DexKitBridge.create(loader, true);
                long end = SystemClock.elapsedRealtime();
                createdElapsedMs = end - start;
                if (bridge.isValid()) {
                    bridge.setThreadNum(THREAD_NUM);
                    createdDexNum = bridge.getDexNum();
                    log.info("resolver dexkit bridge created trigger=" + trigger
                            + " loaderIdentity=" + loaderId
                            + " elapsedMs=" + createdElapsedMs
                            + " dexNum=" + createdDexNum
                            + " rebuild=" + rebuildCount);
                    return bridge;
                }
                return null;
            } catch (Throwable throwable) {
                log.error("resolver dexkit bridge creation failed trigger=" + trigger, throwable);
                return null;
            }
        }
    }

    /** Releases the native bridge once resolution is finished. */
    void close() {
        synchronized (lock) {
            if (bridge != null) {
                log.info("resolver dexkit bridge closed dexNum="
                        + (bridge.isValid() ? bridge.getDexNum() : -1));
            }
            closeBridge();
            bridgeClosed = true;
        }
    }

    /** Lifecycle tests: whether the native bridge is released. */
    boolean isBridgeClosedForTests() {
        synchronized (lock) {
            return bridgeClosed;
        }
    }

    private void closeBridge() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Throwable ignored) {
            }
            bridge = null;
        }
    }
}
