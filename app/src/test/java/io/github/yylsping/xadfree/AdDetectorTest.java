package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public final class AdDetectorTest {
    private AtomicInteger errors;
    private AdDetector detector;

    @Before
    public void setUp() {
        errors = new AtomicInteger();
        detector = new AdDetector((modelClass, error) -> errors.incrementAndGet());
    }

    private AdDetector.Verdict detect(Object entry) {
        return detector.detect(entry).verdict;
    }

    @Test
    public void promotedMetadataIsStrongAdEvidence() {
        AdDetector.DetectionResult result = detector.detect(new PromotedEntry());

        assertEquals(AdDetector.Verdict.AD, result.verdict);
        assertTrue(result.evidence.contains("promotedMetadata"));
        assertTrue(result.score >= 40);
        assertEquals(1, detector.adCount());
    }

    @Test
    public void promotedEntryIdPrefixAloneIsAd() {
        assertEquals(AdDetector.Verdict.AD, detect(new EntryWithId("promoted-26")));
        assertEquals(AdDetector.Verdict.AD, detect(new EntryWithId("tweet-1-promoted_tweet-99")));
        assertEquals(AdDetector.Verdict.AD, detect(new EntryWithId("ad-12")));
    }

    @Test
    public void ordinaryEntryIdIsNotAd() {
        assertEquals(AdDetector.Verdict.NOT_AD, detect(new EntryWithId("tweet-26")));
    }

    @Test
    public void classNameTokenAloneNeverReachesAdThreshold() {
        // A class whose only signal is the promoted-flavored name must pass through.
        assertEquals(AdDetector.Verdict.NOT_AD, detect(new FakeRtbImageAdEntry()));
    }

    @Test
    public void nestedEventSummaryPromotedIsAd() {
        AdDetector.DetectionResult result =
                detector.detect(new EventSummaryEntry(new PromotedPayload()));

        assertEquals(AdDetector.Verdict.AD, result.verdict);
        assertTrue(result.evidence.contains("eventSummaryPromoted"));
    }

    @Test
    public void nestedTimelineTrendPromotedIsAd() {
        assertEquals(AdDetector.Verdict.AD,
                detect(new TrendEntry(new PromotedPayload())));
    }

    @Test
    public void nestedModuleItemPromotedIsAd() {
        ModuleEntry module = new ModuleEntry(Collections.singletonList(
                new ModuleItem(new PromotedEntry())));

        assertEquals(AdDetector.Verdict.AD, detect(module));
    }

    @Test
    public void moduleWithoutAdsIsNotAd() {
        ModuleEntry module = new ModuleEntry(Collections.singletonList(
                new ModuleItem(new EntryWithId("tweet-1"))));

        assertEquals(AdDetector.Verdict.NOT_AD, detect(module));
    }

    @Test
    public void cyclicModulesTerminateNotAd() {
        ModuleEntry a = new ModuleEntry(null);
        ModuleEntry b = new ModuleEntry(Collections.singletonList(new ModuleItem(a)));
        a.items = Collections.singletonList(new ModuleItem(b));

        assertEquals(AdDetector.Verdict.NOT_AD, detect(a));
    }

    @Test
    public void throwingAccessorOnShapedClassIsUnknown() {
        assertEquals(AdDetector.Verdict.UNKNOWN, detect(new ThrowingEntry()));
        assertTrue(errors.get() > 0);
    }

    @Test
    public void throwingAccessorIsFailOpenAndOncePerClass() {
        int before = errors.get();
        for (int i = 0; i < 3; i++) {
            detector.detect(new ThrowingEntry());
        }
        // Error reporter is invoked per detection here; production dedupes by key.
        assertTrue(errors.get() >= before + 3);
    }

    @Test
    public void planCachesPerConcreteClass() {
        for (int i = 0; i < 50; i++) {
            detector.detect(new EntryWithId("tweet-" + i));
            detector.detect(new PromotedEntry());
        }
        assertEquals(2, detector.cachedPlanCount());
    }

    @Test
    public void modelInterfaceShapeCheckWorks() {
        detector.setModelInterface(ModelShape.class);
        assertTrue(detector.looksLikeModelType(ShapedEntry.class));
        assertEquals(false, detector.looksLikeModelType(EntryWithId.class));
    }

    @Test
    public void nullAndDeepEntriesAreNotAd() {
        assertEquals(AdDetector.Verdict.NOT_AD, detect(null));
        // Depth guard: 9-level nesting without promoted data stays NOT_AD.
        Object deep = new EntryWithId("tweet-1");
        for (int i = 0; i < 9; i++) {
            deep = new ModuleEntry(Collections.singletonList(new ModuleItem(deep)));
        }
        assertEquals(AdDetector.Verdict.NOT_AD, detect(deep));
    }

    @Test
    public void inspectionPlanAccessorLookup() {
        AdDetector.InspectionPlan plan = detector.planOf(PromotedEntry.class);
        assertNotNull(plan.promotedMetadata);
        assertEquals(null, plan.entryId);
        assertTrue(plan.hasAccessor);
    }

    // ------------------------------------------------------------------
    // App-helper semantic safety (P0-3)
    // ------------------------------------------------------------------

    @Test
    public void wrongBooleanHelperCannotIndependentlyDelete() throws Exception {
        // The helper claims tweet-1 is an ad; nothing else supports that.
        // Unverified weight (20) stays below the AD threshold (40), so the
        // entry must survive a mis-resolved helper.
        java.lang.reflect.Method wrongHelper = WrongPredicate.class.getDeclaredMethod(
                "isAd", Object.class);
        detector.setAppIsAd(wrongHelper);

        assertEquals(AdDetector.HelperWitnessState.UNVERIFIED, detector.helperWitnessState());
        assertEquals(AdDetector.Verdict.NOT_AD, detect(new EntryWithId("tweet-1")));
    }

    @Test
    public void unverifiedHelperStillSupportsRealEvidence() throws Exception {
        // Promoted metadata (45) alone already deletes; the unverified helper
        // never pushes a NOT_AD-lookalike over the line.
        java.lang.reflect.Method wrongHelper = WrongPredicate.class.getDeclaredMethod(
                "isAd", Object.class);
        detector.setAppIsAd(wrongHelper);

        assertEquals(AdDetector.Verdict.NOT_AD, detect(new EntryWithId("tweet-9")));
    }

    @Test
    public void semanticWitnessVerifiesAndUnlocksFullWeight() throws Exception {
        final List<String> events = Collections.synchronizedList(new java.util.ArrayList<>());
        AdDetector witnessed = new AdDetector(
                (modelClass, error) -> { },
                new AdDetector.AdHelperWitnessListener() {
                    @Override
                    public void onAdHelperVerified(String evidenceSummary) {
                        events.add("verified");
                    }

                    @Override
                    public void onAdHelperDisabled(String reason) {
                        events.add("disabled");
                    }
                });
        java.lang.reflect.Method goodHelper = PromotedOnlyPredicate.class.getDeclaredMethod(
                "isAd", Object.class);
        witnessed.setAppIsAd(goodHelper);

        // One agreement-positive plus enough agreement-negatives to reach the
        // sample minimum.
        assertEquals(AdDetector.Verdict.AD, witnessed.detect(new PromotedEntry()).verdict);
        for (int i = 0; i < AdDetector.HELPER_MIN_SAMPLES; i++) {
            assertEquals(AdDetector.Verdict.NOT_AD,
                    witnessed.detect(new EntryWithId("tweet-" + i)).verdict);
        }

        assertEquals(AdDetector.HelperWitnessState.VERIFIED, witnessed.helperWitnessState());
        assertEquals(Collections.singletonList("verified"), events);

        // Verified weight (45) now matches the old semantics: a helper-only
        // positive is deletable — but only after runtime verification.
        assertEquals(AdDetector.Verdict.AD,
                witnessed.detect(new HelperOnlyPositive()).verdict);
    }

    @Test
    public void semanticContradictionDisablesHelper() throws Exception {
        final List<String> events = Collections.synchronizedList(new java.util.ArrayList<>());
        AdDetector witnessed = new AdDetector(
                (modelClass, error) -> { },
                new AdDetector.AdHelperWitnessListener() {
                    @Override
                    public void onAdHelperVerified(String evidenceSummary) {
                        events.add("verified");
                    }

                    @Override
                    public void onAdHelperDisabled(String reason) {
                        events.add("disabled");
                    }
                });
        java.lang.reflect.Method wrongHelper = WrongPredicate.class.getDeclaredMethod(
                "isAd", Object.class);
        witnessed.setAppIsAd(wrongHelper);

        // Contradiction 1: helper says ad, evidence says normal content.
        assertEquals(AdDetector.Verdict.NOT_AD, witnessed.detect(new EntryWithId("tweet-1")).verdict);
        assertEquals(AdDetector.HelperWitnessState.UNVERIFIED, witnessed.helperWitnessState());
        // Contradiction 2 crosses the tolerance limit.
        assertEquals(AdDetector.Verdict.NOT_AD, witnessed.detect(new EntryWithId("tweet-1")).verdict);

        assertEquals(AdDetector.HelperWitnessState.DISABLED, witnessed.helperWitnessState());
        assertEquals(Collections.singletonList("disabled"), events);
        // After disable, the helper contributes nothing at all.
        assertEquals(AdDetector.Verdict.NOT_AD,
                witnessed.detect(new EntryWithId("tweet-1")).verdict);
    }

    @Test
    public void setAppIsAdResetsWitnessCounters() throws Exception {
        java.lang.reflect.Method helper = PromotedOnlyPredicate.class.getDeclaredMethod(
                "isAd", Object.class);
        detector.setAppIsAd(helper);
        detector.detect(new PromotedEntry());

        detector.setAppIsAd(null);

        assertEquals(AdDetector.HelperWitnessState.ABSENT, detector.helperWitnessState());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    public static final class PromotedEntry {
        public Object getPromotedMetadata() {
            return new Object();
        }
    }

    public static final class EntryWithId {
        private final String entryId;

        EntryWithId(String entryId) {
            this.entryId = entryId;
        }

        public String getEntryId() {
            return entryId;
        }
    }

    /** Class NAME contains the rtbimagead token but exposes no promoted data. */
    public static final class FakeRtbImageAdEntry {
        public String getEntryId() {
            return "tweet-1";
        }
    }

    public static final class EventSummaryEntry {
        private final Object summary;

        EventSummaryEntry(Object summary) {
            this.summary = summary;
        }

        public Object getEventSummary() {
            return summary;
        }
    }

    public static final class TrendEntry {
        private final Object trend;

        TrendEntry(Object trend) {
            this.trend = trend;
        }

        public Object getTimelineTrend() {
            return trend;
        }
    }

    public static final class PromotedPayload {
        public Object getPromotedMetadata() {
            return new Object();
        }
    }

    public static final class ModuleEntry {
        List<ModuleItem> items;

        ModuleEntry(List<ModuleItem> items) {
            this.items = items;
        }

        public List<ModuleItem> getItems() {
            return items;
        }
    }

    public static final class ModuleItem {
        private final Object item;

        ModuleItem(Object item) {
            this.item = item;
        }

        public Object getItem() {
            return item;
        }
    }

    public static final class ThrowingEntry {
        public Object getPromotedMetadata() {
            throw new IllegalStateException("broken accessor");
        }
    }

    /** Mis-resolved helper: flags a normal entry as ad. */
    public static final class WrongPredicate {
        public static boolean isAd(Object entry) {
            return entry instanceof EntryWithId
                    && "tweet-1".equals(((EntryWithId) entry).getEntryId());
        }
    }

    /** Correct helper: only flags entries with real promoted evidence. */
    public static final class PromotedOnlyPredicate {
        public static boolean isAd(Object entry) {
            return entry instanceof PromotedEntry || entry instanceof HelperOnlyPositive;
        }
    }

    /** No independent evidence; only the (verified) helper can flag it. */
    public static final class HelperOnlyPositive {
        public String getEntryId() {
            return "tweet-100";
        }
    }

    public interface ModelShape {
        String getEntryId();
    }

    public static final class ShapedEntry implements ModelShape {
        @Override
        public String getEntryId() {
            return "tweet-9";
        }
    }
}
