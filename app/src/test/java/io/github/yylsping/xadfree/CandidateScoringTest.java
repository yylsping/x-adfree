package io.github.yylsping.xadfree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public final class CandidateScoringTest {
    private static Set<String> strings(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static CandidateScoring.CandidateFeatures features(
            Set<String> usingStrings, String methodName, boolean cpsShape,
            boolean shapeConflict, boolean flowCollector, boolean inUrtPackage) {
        return new CandidateScoring.CandidateFeatures(
                usingStrings, methodName, cpsShape, shapeConflict, flowCollector, inUrtPackage);
    }

    @Test
    public void fullStrongFingerprintReachesStaticAcceptance() {
        CandidateScoring.Report report = CandidateScoring.scoreReport(features(
                strings(CandidateScoring.STRING_PRIMARY, CandidateScoring.STRING_SECONDARY,
                        "minimum_spacing", "brand_safety"),
                "emit", true, false, true, true));

        assertTrue("score=" + report.score,
                report.score >= CandidateScoring.ACCEPT_STATIC);
        assertTrue(report.evidence.contains("strings:both"));
        assertTrue(report.evidence.contains("cpsShape"));
        assertTrue(report.evidence.contains("flowCollector"));
    }

    @Test
    public void primaryStringAloneIsNotStaticAcceptance() {
        CandidateScoring.Report report = CandidateScoring.scoreReport(features(
                strings(CandidateScoring.STRING_PRIMARY), "emit", true, false, true, false));

        assertTrue(report.score < CandidateScoring.ACCEPT_STATIC);
        assertTrue(report.score >= 0);
    }

    @Test
    public void packageAloneNeverAccepts() {
        CandidateScoring.Report report = CandidateScoring.scoreReport(features(
                Collections.<String>emptySet(), "emit", true, false, false, true));

        assertTrue(report.score < CandidateScoring.ACCEPT_WITH_WITNESS);
    }

    @Test
    public void shapeConflictIsPenalizedHard() {
        CandidateScoring.Report clean = CandidateScoring.scoreReport(features(
                strings(CandidateScoring.STRING_PRIMARY), "emit", true, false, false, false));
        CandidateScoring.Report conflicted = CandidateScoring.scoreReport(features(
                strings(CandidateScoring.STRING_PRIMARY), "emit", false, true, false, false));

        assertEquals(clean.score - 25 - 15, conflicted.score);
        assertTrue(conflicted.evidence.contains("shapeConflict"));
    }

    @Test
    public void noEvidenceScoresZeroOrBelow() {
        CandidateScoring.Report report = CandidateScoring.scoreReport(features(
                Collections.<String>emptySet(), "bind", false, false, false, false));

        assertEquals(0, report.score);
        assertEquals("none", report.evidence);
    }

    @Test
    public void ambiguityDetectsCloseTopScores() {
        List<CandidateScoring.ScoredCandidate> ranked = Arrays.asList(
                new CandidateScoring.ScoredCandidate("LA;", "LA;->a()", 55, "x"),
                new CandidateScoring.ScoredCandidate("LB;", "LB;->b()", 50, "y"));
        List<CandidateScoring.ScoredCandidate> clear = Arrays.asList(
                new CandidateScoring.ScoredCandidate("LA;", "LA;->a()", 95, "x"),
                new CandidateScoring.ScoredCandidate("LB;", "LB;->b()", 40, "y"));

        assertTrue(CandidateScoring.isAmbiguousTop(ranked));
        assertFalse(CandidateScoring.isAmbiguousTop(clear));
        assertFalse(CandidateScoring.isAmbiguousTop(
                Collections.singletonList(new CandidateScoring.ScoredCandidate(
                        "LA;", "LA;->a()", 55, "x"))));
    }
}
