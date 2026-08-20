package io.github.yylsping.xadfree;

import java.util.ArrayList;
import java.util.List;

/** Shared JVM fixtures for verifier/witness/hook/coordinator tests. */
public final class WitnessFixtures {
    private WitnessFixtures() {
    }

    /** Structural stand-in for kotlin.coroutines.Continuation. */
    public interface FakeContinuation {
        Object getContext();
    }

    /** Structural stand-in for the FlowCollector emit interface. */
    public interface FakeFlowCollector {
        Object emit(Object value, FakeContinuation continuation);
    }

    /** Structural stand-in for UrtTimelineItem. */
    public interface FakeUrtItem {
        String getEntryId();

        long getSortIndex();
    }

    /** A correctly shaped emit override. */
    public static final class GoodEmit implements FakeFlowCollector {
        public Object emit(Object value, FakeContinuation continuation) {
            return "good";
        }
    }

    /** A second correctly shaped emit override (ambiguity fixture). */
    public static final class RivalEmit implements FakeFlowCollector {
        public Object emit(Object value, FakeContinuation continuation) {
            return "rival";
        }
    }

    /** Correct parameter shape but no interface override anywhere. */
    public static final class NoOverrideEmit {
        public Object emit(Object value, FakeContinuation continuation) {
            return "orphan";
        }
    }

    /** Timeline entry with an entryId accessor (witness-shape fixture). */
    public static final class EntryWithId implements FakeUrtItem {
        private final String entryId;

        public EntryWithId(String entryId) {
            this.entryId = entryId;
        }

        @Override
        public String getEntryId() {
            return entryId;
        }

        @Override
        public long getSortIndex() {
            return 0L;
        }
    }

    /** Timeline entry carrying promoted metadata (strong AD evidence). */
    public static final class PromotedEntry implements FakeUrtItem {
        @Override
        public String getEntryId() {
            return "promoted-1";
        }

        @Override
        public long getSortIndex() {
            return 0L;
        }

        public Object getPromotedMetadata() {
            return new Object();
        }
    }

    public static List<Object> timeline(Object... elements) {
        List<Object> list = new ArrayList<>();
        for (Object element : elements) {
            list.add(element);
        }
        return list;
    }

    /** A List implementation outside the replacement allowlist (P2-3). */
    public static List<Object> foreignList(Object... elements) {
        return java.util.Arrays.asList(elements);
    }
}
