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
 *
 * <p>The app's own boolean helper is never trusted blindly (P0-3): until a
 * runtime semantic witness correlates it with independent evidence on real
 * items it only contributes a supporting weight below the AD threshold, and a
 * contradicting helper is disabled entirely. Correlation uses verdicts only —
 * no tweet content is recorded.
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

    /** Lifecycle notifications for the app-helper semantic witness (P0-3). */
    interface AdHelperWitnessListener {
        void onAdHelperVerified(String evidenceSummary);

        void onAdHelperDisabled(String reason);
    }

    /** Semantic-witness lifecycle of the injected app helper. */
    enum HelperWitnessState { ABSENT, UNVERIFIED, VERIFIED, DISABLED }

    private static final int MAX_NESTING_DEPTH = 8;
    private static final int AD_THRESHOLD = 40;
    /** Below the threshold: an unverified helper can never delete alone (P0-3). */
    static final int APP_HELPER_WEIGHT_UNVERIFIED = 20;
    /** Full strong weight, unlocked only after semantic verification. */
    static final int APP_HELPER_WEIGHT_VERIFIED = 45;
    /** Samples needed before the semantic witness may verify the helper. */
    static final int HELPER_MIN_SAMPLES = 12;
    /** Contradictions tolerated before the helper is disabled. */
    static final int HELPER_MAX_CONTRADICTIONS = 1;
    private static final Boolean PRESENT = Boolean.TRUE;

    private final ConcurrentMap<Class<?>, InspectionPlan> plans = new ConcurrentHashMap<>();
    private final ErrorReporter errorReporter;
    private final AtomicLong inspectedCount = new AtomicLong();
    private final AtomicLong adCount = new AtomicLong();

    /** The app's own static isAd(modelInterface) predicate, when resolved. */
    private volatile Method appIsAd;

    /** Optional shape check: elements may implement the model interface. */
    private volatile Class<?> modelInterface;

    private final AdHelperWitnessListener helperListener;
    private final Object helperLock = new Object();
    private HelperWitnessState helperState = HelperWitnessState.ABSENT;
    private int helperAgreePositive;
    private int helperAgreeNegative;
    private int helperContradictions;
    private int helperSamples;

    AdDetector(ErrorReporter errorReporter) {
        this(errorReporter, null);
    }

    AdDetector(ErrorReporter errorReporter, AdHelperWitnessListener helperListener) {
        this.errorReporter = errorReporter;
        this.helperListener = helperListener;
    }

    void setAppIsAd(Method method) {
        this.appIsAd = method;
        synchronized (helperLock) {
            helperState = method == null
                    ? HelperWitnessState.ABSENT : HelperWitnessState.UNVERIFIED;
            helperAgreePositive = 0;
            helperAgreeNegative = 0;
            helperContradictions = 0;
            helperSamples = 0;
        }
    }

    void setModelInterface(Class<?> type) {
        this.modelInterface = type;
    }

    HelperWitnessState helperWitnessState() {
        synchronized (helperLock) {
            return helperState;
        }
    }

    String describeHelperWitness() {
        synchronized (helperLock) {
            return "state=" + helperState + " samples=" + helperSamples
                    + " agreePositive=" + helperAgreePositive
                    + " agreeNegative=" + helperAgreeNegative
                    + " contradictions=" + helperContradictions;
        }
    }

    DetectionResult detect(Object entry) {
        DetectionResult result = inspect(entry, 0, null);
        if (result.verdict == Verdict.AD) {
            adCount.incrementAndGet();
        }
        return result;
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
        int independentScore = 0;
        StringBuilder evidence = new StringBuilder();
        boolean reflectionFailed = false;

        // Strong: direct promoted metadata.
        try {
            if (invoke(plan.promotedMetadata, entry) != null) {
                independentScore += 45;
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
                independentScore += 45;
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
                independentScore += 45;
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
                                independentScore += 45;
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
            independentScore += 15;
            evidence.append("|classNameToken");
        }

        // Strong: promoted entry-id prefix.
        try {
            Object entryId = invoke(plan.entryId, entry);
            if (entryId != null && isPromotedEntryId(entryId.toString())) {
                independentScore += 40;
                evidence.append("|entryIdPromoted");
            }
        } catch (Throwable error) {
            reflectionFailed = true;
            reportOnce(entry.getClass(), error);
        }

        // The app's own predicate: supporting evidence until semantically
        // verified; a single wrong boolean can never delete alone (P0-3).
        Method isAd = appIsAd;
        if (isAd != null) {
            try {
                Object verdict = isAd.invoke(null, entry);
                if (Boolean.TRUE.equals(verdict)) {
                    sampleHelperSemantics(plan, true, independentScore, reflectionFailed);
                    int weight = helperWeight();
                    if (weight > 0) {
                        evidence.append(weight == APP_HELPER_WEIGHT_VERIFIED
                                ? "|appIsAdVerified" : "|appIsAdUnverified");
                        independentScore += weight;
                    }
                } else if (verdict != null) {
                    sampleHelperSemantics(plan, false, independentScore, reflectionFailed);
                }
            } catch (Throwable error) {
                reflectionFailed = true;
                reportOnce(entry.getClass(), error);
            }
        }

        int score = independentScore;
        if (score >= AD_THRESHOLD) {
            return new DetectionResult(Verdict.AD, score, trim(evidence));
        }
        if (reflectionFailed && plan.hasAccessor) {
            return new DetectionResult(Verdict.UNKNOWN, score, trim(evidence) + "|reflectionFailed");
        }
        return new DetectionResult(Verdict.NOT_AD, score, trim(evidence));
    }

    /**
     * Correlates the helper verdict with the independent evidence for real
     * timeline-model objects only. Verdict booleans are counted, never
     * content. Transitions notify the (worker-side) listener exactly once.
     */
    private void sampleHelperSemantics(InspectionPlan plan, boolean helperSaysAd,
                                       int independentScore, boolean reflectionFailed) {
        if (!plan.hasAccessor || reflectionFailed) {
            return; // Not a decidable timeline model, or evidence unreliable.
        }
        boolean independentAd = independentScore >= AD_THRESHOLD;
        String disabledReason = null;
        String verifiedSummary = null;
        synchronized (helperLock) {
            if (helperState != HelperWitnessState.UNVERIFIED) {
                return;
            }
            helperSamples++;
            if (helperSaysAd == independentAd) {
                if (helperSaysAd) {
                    helperAgreePositive++;
                } else {
                    helperAgreeNegative++;
                }
            } else {
                helperContradictions++;
            }
            if (helperContradictions > HELPER_MAX_CONTRADICTIONS) {
                helperState = HelperWitnessState.DISABLED;
                appIsAd = null;
                disabledReason = "contradictions=" + helperContradictions
                        + " samples=" + helperSamples;
            } else if (helperSamples >= HELPER_MIN_SAMPLES
                    && helperContradictions == 0
                    && helperAgreePositive >= 1) {
                helperState = HelperWitnessState.VERIFIED;
                verifiedSummary = describeHelperWitnessLocked();
            }
        }
        if (disabledReason != null && helperListener != null) {
            helperListener.onAdHelperDisabled(disabledReason);
        }
        if (verifiedSummary != null && helperListener != null) {
            helperListener.onAdHelperVerified(verifiedSummary);
        }
    }

    private int helperWeight() {
        synchronized (helperLock) {
            if (helperState == HelperWitnessState.VERIFIED) {
                return APP_HELPER_WEIGHT_VERIFIED;
            }
            if (helperState == HelperWitnessState.UNVERIFIED) {
                return APP_HELPER_WEIGHT_UNVERIFIED;
            }
            return 0;
        }
    }

    private String describeHelperWitnessLocked() {
        return "state=" + helperState + " samples=" + helperSamples
                + " agreePositive=" + helperAgreePositive
                + " agreeNegative=" + helperAgreeNegative
                + " contradictions=" + helperContradictions;
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
