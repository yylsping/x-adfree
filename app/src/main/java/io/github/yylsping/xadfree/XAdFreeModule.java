package io.github.yylsping.xadfree;

import android.util.Log;
import android.view.View;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/** Modern Xposed API 102 entry for X 12.3.1-release.0. */
public final class XAdFreeModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.twitter.android";
    private static final String TAG = "XAdFree";
    private static final int AD_BADGE_ID = 0x7f0b12a3;

    private final CollapsedViewRegistry collapsedRows = new CollapsedViewRegistry();
    private final Set<String> reportedInspectionErrors = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedRuntimeErrors = ConcurrentHashMap.newKeySet();
    private final AdDetector adDetector = new AdDetector(this::reportInspectionErrorOnce);
    private final UrtListFilter urtFilter = new UrtListFilter(adDetector);
    private boolean targetProcess;
    private boolean installed;

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
            if (installed) {
                return;
            }
            installed = true;
        }

        try {
            TargetBindings bindings = new TargetBindings(param.getClassLoader());
            installTweetBinderHook(bindings);
            installAdBadgeHook(bindings);
            installUrtHook(bindings);
            log(Log.INFO, TAG, "hooks installed for X 12.3.1-release.0");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "failed to resolve target bindings", error);
        }
    }

    private void installTweetBinderHook(TargetBindings bindings) {
        hook(bindings.tweetBinder).intercept(chain -> {
            Object holder = chain.getArg(0);
            Object timelineItem = chain.getArg(1);
            if (holder == null || timelineItem == null) {
                return chain.proceed();
            }

            View row;
            try {
                row = bindings.boundRow(holder);
                if (row == null) {
                    return chain.proceed();
                }
                if (bindings.isLegacyPromotedItem(timelineItem)) {
                    collapsedRows.collapse(row);
                    return null;
                }
            } catch (ReflectiveOperationException error) {
                reportRuntimeErrorOnce("legacy-item", error);
                return chain.proceed();
            }

            collapsedRows.restore(row);
            return chain.proceed();
        });
    }

    private void installAdBadgeHook(TargetBindings bindings) {
        hook(bindings.adBadgeUpdater).intercept(chain -> {
            Object result = chain.proceed();
            try {
                View badge = bindings.badgeFromUpdater(chain.getThisObject());
                if (badge == null || badge.getId() != AD_BADGE_ID) {
                    return result;
                }
                View row = findTimelineRow(badge);
                if (row == null) {
                    return result;
                }
                if (badge.getVisibility() == View.VISIBLE) {
                    collapsedRows.collapse(row);
                } else {
                    collapsedRows.restore(row);
                }
            } catch (ReflectiveOperationException error) {
                reportRuntimeErrorOnce("ad-badge", error);
            }
            return result;
        });
    }

    private void installUrtHook(TargetBindings bindings) {
        hook(bindings.urtEmit).intercept(chain -> {
            Object argument = chain.getArg(0);
            if (!(argument instanceof List<?>)) {
                return chain.proceed();
            }
            List<?> incoming = (List<?>) argument;
            List<?> filtered = urtFilter.filter(incoming);
            if (filtered == incoming) {
                return chain.proceed();
            }
            Object[] args = {filtered, chain.getArg(1)};
            return chain.proceed(args);
        });
    }

    private static View findTimelineRow(View child) {
        View current = child;
        while (current != null) {
            if (TargetBindings.TIMELINE_ROW_CLASS.equals(current.getClass().getName())) {
                return current;
            }
            if (!(current.getParent() instanceof View)) {
                return null;
            }
            current = (View) current.getParent();
        }
        return null;
    }

    private void reportInspectionErrorOnce(Class<?> modelClass, Throwable error) {
        String key = modelClass.getName() + ':' + error.getClass().getName();
        if (reportedInspectionErrors.add(key)) {
            log(Log.ERROR, TAG, "URT inspection failed for " + modelClass.getName(), error);
        }
    }

    private void reportRuntimeErrorOnce(String operation, Throwable error) {
        String key = operation + ':' + error.getClass().getName();
        if (reportedRuntimeErrors.add(key)) {
            log(Log.ERROR, TAG, operation + " inspection failed", error);
        }
    }
}
