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
}
