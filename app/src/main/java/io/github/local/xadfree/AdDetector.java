package io.github.local.xadfree;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Detects promoted URT models without repeating reflection lookups for every item. */
final class AdDetector {
    interface ErrorReporter {
        void report(Class<?> modelClass, Throwable error);
    }

    private static final int MAX_NESTING_DEPTH = 8;
    private static final Boolean PRESENT = Boolean.TRUE;

    private final ConcurrentMap<Class<?>, InspectionPlan> plans = new ConcurrentHashMap<>();
    private final ErrorReporter errorReporter;

    AdDetector(ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }

    boolean isAdvertisement(Object entry) {
        return inspect(entry, 0, null);
    }

    int cachedPlanCount() {
        return plans.size();
    }

    private boolean inspect(
            Object entry,
            int depth,
            IdentityHashMap<Object, Boolean> ancestors) {
        if (entry == null || depth > MAX_NESTING_DEPTH) {
            return false;
        }

        InspectionPlan plan = planFor(entry.getClass());
        try {
            if (invoke(plan.promotedMetadata, entry) != null) {
                return true;
            }

            Object eventSummary = invoke(plan.eventSummary, entry);
            if (eventSummary != null
                    && invoke(planFor(eventSummary.getClass()).promotedMetadata, eventSummary) != null) {
                return true;
            }

            Object trend = invoke(plan.timelineTrend, entry);
            if (trend != null
                    && invoke(planFor(trend.getClass()).promotedMetadata, trend) != null) {
                return true;
            }

            Object items = invoke(plan.items, entry);
            if (items instanceof Iterable<?>) {
                IdentityHashMap<Object, Boolean> visited = ancestors;
                if (visited == null) {
                    visited = new IdentityHashMap<>();
                }
                if (visited.put(entry, PRESENT) != null) {
                    return false;
                }
                try {
                    for (Object moduleItem : (Iterable<?>) items) {
                        if (moduleItem == null) {
                            continue;
                        }
                        Object nested = invoke(planFor(moduleItem.getClass()).item, moduleItem);
                        if (nested != null && inspect(nested, depth + 1, visited)) {
                            return true;
                        }
                    }
                } finally {
                    visited.remove(entry);
                }
            }

            if (plan.promotedTypeName) {
                return true;
            }

            Object entryId = invoke(plan.entryId, entry);
            return entryId != null && isPromotedEntryId(entryId.toString());
        } catch (Throwable error) {
            errorReporter.report(entry.getClass(), unwrap(error));
            return false;
        }
    }

    private InspectionPlan planFor(Class<?> type) {
        InspectionPlan existing = plans.get(type);
        if (existing != null) {
            return existing;
        }
        InspectionPlan created = new InspectionPlan(type);
        InspectionPlan raced = plans.putIfAbsent(type, created);
        return raced != null ? raced : created;
    }

    private static Object invoke(Method method, Object target) throws ReflectiveOperationException {
        return method == null ? null : method.invoke(target);
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getTargetException() != null) {
            return ((InvocationTargetException) error).getTargetException();
        }
        return error;
    }

    private static boolean isPromotedEntryId(String id) {
        return containsIgnoreCase(id, "promoted-")
                || containsIgnoreCase(id, "promoted_tweet")
                || containsIgnoreCase(id, "promotedtweet")
                || startsWithIgnoreCase(id, "ad-")
                || startsWithIgnoreCase(id, "ads-");
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        int last = value.length() - needle.length();
        for (int index = 0; index <= last; index++) {
            if (value.regionMatches(true, index, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length()
                && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static final class InspectionPlan {
        final Method promotedMetadata;
        final Method eventSummary;
        final Method timelineTrend;
        final Method items;
        final Method item;
        final Method entryId;
        final boolean promotedTypeName;

        InspectionPlan(Class<?> type) {
            promotedMetadata = findPublicNoArg(type, "getPromotedMetadata");
            eventSummary = findPublicNoArg(type, "getEventSummary");
            timelineTrend = findPublicNoArg(type, "getTimelineTrend");
            items = findPublicNoArg(type, "getItems");
            item = findPublicNoArg(type, "getItem");
            entryId = findPublicNoArg(type, "getEntryId");
            String className = type.getName().toLowerCase(Locale.ROOT);
            promotedTypeName = className.contains("rtbimagead")
                    || className.contains("promotedcontent");
        }

        private static Method findPublicNoArg(Class<?> type, String name) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }
}
