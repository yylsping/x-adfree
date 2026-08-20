package io.github.yylsping.xadfree;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tri-state advertisement detection for URT entry objects.
 *
 * <p>Detection is semantic, never name-based alone: promoted metadata accessors,
 * promoted entry-id prefixes, nested event-summary/trend/module promoted
 * metadata, and (when resolved) the app's own isAd predicate all contribute
 * score. Class-name tokens only ever add a bonus that cannot reach the AD
 * threshold by itself. Reflection failures on expected accessors yield
 * UNKNOWN, and UNKNOWN content is always passed through (fail-open).
 */
final class AdDetector {
    enum Verdict { AD, NOT_AD, UNKNOWN }

    static final class DetectionResult {
        final Verdict verdict;
        final int score;
        final String evidence;

        DetectionResult(Verdict verdict, int score, String evidence) {
            this.verdict = verdict;
            this.score = score;
            this.evidence = evidence;
        }
    }

    interface ErrorReporter {
        void report(Class<?> modelClass, Throwable error);
    }

    private static final int MAX_NESTING_DEPTH = 8;
    private static final int AD_THRESHOLD = 40;
    private static final Boolean PRESENT = Boolean.TRUE;

    private final ConcurrentMap<Class<?>, InspectionPlan> plans = new ConcurrentHashMap<>();
    private final ErrorReporter errorReporter;
    private final AtomicLong inspectedCount = new AtomicLong();
    private final AtomicLong adCount = new AtomicLong();

    /** The app's own static isAd(modelInterface) predicate, when resolved. */
    private volatile Method appIsAd;

    /** Optional shape check: elements may implement the model interface. */
    private volatile Class<?> modelInterface;

    AdDetector(ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }

    void setAppIsAd(Method method) {
        this.appIsAd = method;
    }

    void setModelInterface(Class<?> type) {
        this.modelInterface = type;
    }

    DetectionResult detect(Object entry) {
        DetectionResult result = inspect(entry, 0, null);
        if (result.verdict == Verdict.AD) {
            adCount.incrementAndGet();
        }
        return result;
    }

    /** @deprecated tri-state replacement kept for call-site compatibility. */
    @Deprecated
    boolean isAdvertisement(Object entry) {
        return detect(entry).verdict == Verdict.AD;
    }

    long inspectedCount() {
        return inspectedCount.get();
    }

    long adCount() {
        return adCount.get();
    }

    int cachedPlanCount() {
        return plans.size();
    }

    /** Shared plan lookup for witnesses that need accessor shapes. */
    InspectionPlan planOf(Class<?> type) {
        return planFor(type);
    }

    boolean looksLikeModelType(Class<?> type) {
        Class<?> model = modelInterface;
        return model != null && model.isAssignableFrom(type);
    }

    private DetectionResult inspect(Object entry, int depth,
                                    IdentityHashMap<Object, Boolean> ancestors) {
        if (entry == null || depth > MAX_NESTING_DEPTH) {
            return new DetectionResult(Verdict.NOT_AD, 0, "nullOrTooDeep");
        }
        inspectedCount.incrementAndGet();

        InspectionPlan plan = planFor(entry.getClass());
        int score = 0;
        StringBuilder evidence = new StringBuilder();
        boolean reflectionFailed = false;

        // Strong: the app's own predicate.
        Method isAd = appIsAd;
        if (isAd != null) {
            try {
                Object verdict = isAd.invoke(null, entry);
                if (Boolean.TRUE.equals(verdict)) {
                    score += 45;
                    evidence.append("|appIsAd");
                }
            } catch (Throwable error) {
                reflectionFailed = true;
                reportOnce(entry.getClass(), error);
            }
        }

        // Strong: direct promoted metadata.
        try {
            if (invoke(plan.promotedMetadata, entry) != null) {
                score += 45;
                evidence.append("|promotedMetadata");
            }
        } catch (Throwable error) {
            reflectionFailed = true;
            reportOnce(entry.getClass(), error);
        }

        // Strong: nested event summary / trend promoted metadata.
        try {
            Object eventSummary = invoke(plan.eventSummary, entry);
            if (eventSummary != null && invoke(
                    planFor(eventSummary.getClass()).promotedMetadata, eventSummary) != null) {
                score += 45;
                evidence.append("|eventSummaryPromoted");
            }
        } catch (Throwable error) {
            reflectionFailed = true;
            reportOnce(entry.getClass(), error);
        }
        try {
            Object trend = invoke(plan.timelineTrend, entry);
            if (trend != null && invoke(
                    planFor(trend.getClass()).promotedMetadata, trend) != null) {
                score += 45;
                evidence.append("|timelineTrendPromoted");
            }
        } catch (Throwable error) {
            reflectionFailed = true;
            reportOnce(entry.getClass(), error);
        }

        // Strong: promoted item nested inside a module.
        try {
            Object items = invoke(plan.items, entry);
            if (items instanceof Iterable<?>) {
                IdentityHashMap<Object, Boolean> visited = ancestors;
                if (visited == null) {
                    visited = new IdentityHashMap<>();
                }
                if (visited.put(entry, PRESENT) != null) {
                    return new DetectionResult(Verdict.NOT_AD, 0, "cycleGuard");
                }
                try {
                    for (Object moduleItem : (Iterable<?>) items) {
                        if (moduleItem == null) {
                            continue;
                        }
                        Object nested = invoke(
                                planFor(moduleItem.getClass()).item, moduleItem);
                        if (nested != null) {
                            DetectionResult nestedResult = inspect(nested, depth + 1, visited);
                            if (nestedResult.verdict == Verdict.AD) {
                                score += 45;
                                evidence.append("|moduleItemPromoted");
                                break;
                            }
                        }
                    }
                } finally {
                    visited.remove(entry);
                }
            }
        } catch (Throwable error) {
            reflectionFailed = true;
            reportOnce(entry.getClass(), error);
        }

        // Weak bonus only: promoted-flavored type name. Never decisive alone.
        if (plan.promotedTypeName) {
            score += 15;
            evidence.append("|classNameToken");
        }

        // Strong: promoted entry-id prefix.
        try {
            Object entryId = invoke(plan.entryId, entry);
            if (entryId != null && isPromotedEntryId(entryId.toString())) {
                score += 40;
                evidence.append("|entryIdPromoted");
            }
        } catch (Throwable error) {
            reflectionFailed = true;
            reportOnce(entry.getClass(), error);
        }

        if (score >= AD_THRESHOLD) {
            return new DetectionResult(Verdict.AD, score, trim(evidence));
        }
        if (reflectionFailed && plan.hasAccessor) {
            return new DetectionResult(Verdict.UNKNOWN, score, trim(evidence) + "|reflectionFailed");
        }
        return new DetectionResult(Verdict.NOT_AD, score, trim(evidence));
    }

    private void reportOnce(Class<?> modelClass, Throwable error) {
        if (errorReporter != null) {
            errorReporter.report(modelClass, unwrap(error));
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

    private static String trim(StringBuilder evidence) {
        return evidence.length() == 0 ? "none" : evidence.substring(1);
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

    static boolean isPromotedEntryId(String id) {
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

    /** Cached reflective accessors per runtime model class. */
    static final class InspectionPlan {
        final Method promotedMetadata;
        final Method eventSummary;
        final Method timelineTrend;
        final Method items;
        final Method item;
        final Method entryId;
        final boolean promotedTypeName;
        final boolean hasAccessor;

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
            hasAccessor = promotedMetadata != null || eventSummary != null
                    || timelineTrend != null || items != null || item != null
                    || entryId != null;
        }

        static Method findPublicNoArg(Class<?> type, String name) {
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
