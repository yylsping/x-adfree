package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class WitnessLogicTest {
    @Test
    public void nonListArgumentIsAStrike() {
        assertEquals(WitnessLogic.Decision.STRIKE,
                WitnessLogic.onInvocation(false, null, false));
    }

    @Test
    public void emptyListKeepsWaiting() {
        assertEquals(WitnessLogic.Decision.KEEP_WAITING,
                WitnessLogic.onInvocation(true, Collections.emptyList(), false));
    }

    @Test
    public void nullSampleKeepsWaiting() {
        assertEquals(WitnessLogic.Decision.KEEP_WAITING,
                WitnessLogic.onInvocation(true, null, false));
    }

    @Test
    public void shapedElementPasses() {
        assertEquals(WitnessLogic.Decision.PASSED,
                WitnessLogic.onInvocation(true, Arrays.asList("a"), true));
    }

    @Test
    public void unshapedNonEmptySampleIsAStrike() {
        assertEquals(WitnessLogic.Decision.STRIKE,
                WitnessLogic.onInvocation(true, Arrays.asList("a"), false));
    }

    @Test
    public void strikeLimitMatchesDocumentedBound() {
        assertFalse(WitnessLogic.limitReached(0));
        assertFalse(WitnessLogic.limitReached(1));
        assertFalse(WitnessLogic.limitReached(2));
        assertTrue(WitnessLogic.limitReached(3));
    }

    // ------------------------------------------------------------------
    // Probe evidence (P1-8)
    // ------------------------------------------------------------------

    @Test
    public void singleInvocationNeverConfirms() {
        WitnessLogic.ProbeEvidence evidence = new WitnessLogic.ProbeEvidence();
        evidence.record(true, Arrays.asList("a", "b"), 2, 2, false);

        assertTrue(evidence.likely());
        assertFalse("one invocation must not confirm", evidence.confirmed());
    }

    @Test
    public void twoShapedInvocationsConfirm() {
        WitnessLogic.ProbeEvidence evidence = new WitnessLogic.ProbeEvidence();
        evidence.record(true, Arrays.asList("a", "b"), 2, 2, true);
        evidence.record(true, Arrays.asList("c"), 1, 1, false);

        assertTrue(evidence.confirmed());
    }

    @Test
    public void majorityShapedRatioIsRequired() {
        WitnessLogic.ProbeEvidence evidence = new WitnessLogic.ProbeEvidence();
        // 4 of 8 elements shaped = 0.5, still confirming at the boundary.
        evidence.record(true, Arrays.asList(1, 2, 3, 4), 4, 2, false);
        evidence.record(true, Arrays.asList(5, 6, 7, 8), 4, 2, false);
        assertTrue(evidence.confirmed());

        WitnessLogic.ProbeEvidence sparse = new WitnessLogic.ProbeEvidence();
        // 2 of 8 shaped = 0.25 → not confirmable.
        sparse.record(true, Arrays.asList(1, 2, 3, 4), 4, 1, false);
        sparse.record(true, Arrays.asList(5, 6, 7, 8), 4, 1, false);
        assertFalse(sparse.confirmed());
        assertFalse(sparse.likely());
    }

    @Test
    public void unshapedNonEmptySamplesStrike() {
        WitnessLogic.ProbeEvidence evidence = new WitnessLogic.ProbeEvidence();
        evidence.record(true, Arrays.asList("x"), 1, 0, false);
        evidence.record(true, Arrays.asList("y"), 1, 0, false);
        evidence.record(true, Arrays.asList("z"), 1, 0, false);

        assertTrue(evidence.strikeLimitReached());
        assertFalse(evidence.confirmed());
    }

    @Test
    public void nonListArgumentsStrikeWithoutCountingSamples() {
        WitnessLogic.ProbeEvidence evidence = new WitnessLogic.ProbeEvidence();
        for (int i = 0; i < 3; i++) {
            evidence.record(false, null, 0, 0, false);
        }

        assertTrue(evidence.strikeLimitReached());
        assertEquals(0, evidence.nonEmptyInvocations);
    }

    @Test
    public void emptyListsCarryNoEvidence() {
        WitnessLogic.ProbeEvidence evidence = new WitnessLogic.ProbeEvidence();
        for (int i = 0; i < 5; i++) {
            evidence.record(true, Collections.emptyList(), 0, 0, false);
        }

        assertEquals(0, evidence.nonEmptyInvocations);
        assertFalse(evidence.likely());
        assertFalse(evidence.confirmed());
        assertFalse(evidence.strikeLimitReached());
    }

    @Test
    public void sampleCapLimitsInspectionEffort() {
        assertEquals(16, WitnessLogic.ProbeEvidence.sampleCap(1000));
        assertEquals(3, WitnessLogic.ProbeEvidence.sampleCap(3));
    }
}
