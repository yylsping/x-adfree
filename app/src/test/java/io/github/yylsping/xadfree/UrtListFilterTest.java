package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public final class UrtListFilterTest {
    private AtomicInteger errors;
    private AdDetector detector;
    private UrtListFilter filter;

    @Before
    public void setUp() {
        errors = new AtomicInteger();
        detector = new AdDetector((modelClass, error) -> errors.incrementAndGet());
        filter = new UrtListFilter(detector);
    }

    @Test
    public void noAdsReturnsOriginalList() {
        List<?> input = Arrays.asList(new PlainEntry("tweet-1"), new PlainEntry("tweet-2"));

        assertSame(input, filter.filter(input));
        assertEquals(0, errors.get());
    }

    @Test
    public void promotedMetadataIsRemovedWithLazyPrefixCopy() {
        PlainEntry first = new PlainEntry("tweet-1");
        PlainEntry last = new PlainEntry("tweet-3");
        List<?> input = Arrays.asList(first, new PromotedEntry(), last);

        List<?> result = filter.filter(input);

        assertFalse(result == input);
        assertEquals(Arrays.asList(first, last), result);
    }

    @Test
    public void nestedModuleAdsAreRemoved() {
        ModuleEntry module = new ModuleEntry(
                Collections.singletonList(new ModuleItem(new PlainEntry("promoted-tweet"))));

        assertEquals(AdDetector.Verdict.AD, detector.detect(module).verdict);
    }

    @Test
    public void missingMethodsAreNegativelyCachedPerClass() {
        for (int index = 0; index < 100; index++) {
            assertEquals(AdDetector.Verdict.NOT_AD, detector.detect(new Object()).verdict);
        }

        assertEquals(1, detector.cachedPlanCount());
        assertEquals(0, errors.get());
    }

    @Test
    public void unknownVerdictsPassThrough() {
        Throwing throwing = new Throwing();
        PlainEntry last = new PlainEntry("tweet-2");
        List<?> input = Arrays.asList(throwing, new PromotedEntry(), last);

        // The UNKNOWN entry must survive; only the confirmed AD is removed.
        List<?> result = filter.filter(input);

        assertEquals(Arrays.asList(throwing, last), result);
    }

    @Test
    public void triStateVerdictsForDirectDetection() {
        assertEquals(AdDetector.Verdict.AD,
                detector.detect(new PromotedEntry()).verdict);
        assertEquals(AdDetector.Verdict.NOT_AD,
                detector.detect(new PlainEntry("tweet-1")).verdict);
    }

    public static final class Throwing {
        public Object getPromotedMetadata() {
            throw new IllegalStateException("broken");
        }
    }

    public static final class PlainEntry {
        private final String entryId;

        PlainEntry(String entryId) {
            this.entryId = entryId;
        }

        public String getEntryId() {
            return entryId;
        }
    }

    public static final class PromotedEntry {
        public Object getPromotedMetadata() {
            return new Object();
        }
    }

    public static final class ModuleEntry {
        private final List<ModuleItem> items;

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
}
