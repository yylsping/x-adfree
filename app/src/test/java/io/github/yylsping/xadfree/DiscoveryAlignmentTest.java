package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * P1-1: discovery and scoring features must stay aligned. A feature that can
 * add score but is not a discovery entry would be a ghost — the resolver
 * could never surface a candidate for scoring to credit.
 */
public final class DiscoveryAlignmentTest {
    @Test
    public void everyDiscoveryStringInfluencesScoring() {
        Set<String> discovery = new HashSet<>(Arrays.asList(CandidateScoring.DISCOVERY_STRINGS));

        for (String entry : discovery) {
            int with = CandidateScoring.score(features(entry));
            int without = CandidateScoring.score(features(null));
            assertTrue("string '" + entry + "' does not influence scoring",
                    with > without);
        }
    }

    @Test
    public void everyScoringStringConstantHasADiscoveryEntry() {
        Set<String> discovery = new HashSet<>(Arrays.asList(CandidateScoring.DISCOVERY_STRINGS));

        assertTrue(discovery.contains(CandidateScoring.STRING_PRIMARY));
        assertTrue(discovery.contains(CandidateScoring.STRING_SECONDARY));
        assertTrue(discovery.contains(CandidateScoring.STRING_SPACING_METRIC));
        assertTrue(discovery.contains(CandidateScoring.STRING_SPACING));
        assertTrue(discovery.contains(CandidateScoring.STRING_BRAND_SAFETY));
        assertEquals(5, discovery.size());
    }

    @Test
    public void structuralEntryCoversCandidatesWithoutStrings() {
        // A candidate with no strings at all but perfect shape still scores
        // above zero (the structural discovery entry can surface it), yet can
        // never reach static acceptance without business-string evidence.
        int structuralOnly = CandidateScoring.score(new CandidateScoring.CandidateFeatures(
                Collections.<String>emptySet(), "emit", true, false,
                XTargetVerifier.TriState.YES, true));

        assertTrue("structural-only score=" + structuralOnly, structuralOnly > 0);
        assertTrue("structural-only score=" + structuralOnly,
                structuralOnly < CandidateScoring.ACCEPT_STATIC);
    }

    private static CandidateScoring.CandidateFeatures features(String string) {
        return new CandidateScoring.CandidateFeatures(
                string == null ? Collections.<String>emptySet()
                        : Collections.singleton(string),
                "emit", true, false, XTargetVerifier.TriState.YES, false);
    }
}
