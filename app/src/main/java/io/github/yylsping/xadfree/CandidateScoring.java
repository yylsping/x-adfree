package io.github.yylsping.xadfree;

import java.util.List;

/**
 * Orthogonal-feature scoring for urt_emit candidates. No single feature is
 * sufficient: the accepted threshold requires the high-entropy business
 * strings or several independent structural hits.
 *
 * <p>Every string that can add score here is also a discovery entry in
 * {@link UrtEmitResolver#DISCOVERY_STRINGS} — a feature that could never be
 * discovered would be a ghost (P1-1), and a static test enforces the
 * alignment.
 *
 * <pre>
 * +45 both high-entropy business strings ("Ad removal: " + spacing log tail)
 * +15 one high-entropy business string
 * +15 exact (Object, Continuation) -&gt; Object CPS shape
 * +10 interface override of the emit shape proven (TriState.YES)
 * +10 spacing/brand-safety logic constants present
 * +10 declaring class under com.x.repositories.urt (bonus only, never required)
 *  +5 method still named "emit" (historical seed token)
 * -15 hierarchy has no interface override (TriState.NO; verifier also rejects)
 * -25 shape conflict (static/abstract or wrong parameter/return types)
 * </pre>
 *
 * ACCEPT_STATIC >= 70; ACCEPT_WITH_WITNESS >= 50; below that the candidate
 * only participates in runtime-witness probes. TriState.UNKNOWN credits
 * nothing — unverifiable is not positive (P1-2).
 */
final class CandidateScoring {
    static final String STRING_PRIMARY = "Ad removal: ";
    static final String STRING_SECONDARY = " ads removed (spacing=";
    static final String STRING_SPACING_METRIC = "minimum_spacing_ad_removal";
    static final String STRING_SPACING = "minimum_spacing";
    static final String STRING_BRAND_SAFETY = "brand_safety";

    /** Strings that independently justify a discovery entry (P1-1). */
    static final String[] DISCOVERY_STRINGS = {
            STRING_PRIMARY,
            STRING_SECONDARY,
            STRING_SPACING_METRIC,
            STRING_SPACING,
            STRING_BRAND_SAFETY,
    };

    static final int ACCEPT_STATIC = 70;
    static final int ACCEPT_WITH_WITNESS = 50;
    static final int GAP_AMBIGUITY = 10;
    static final int NO_INTERFACE_OVERRIDE_PENALTY = 15;

    static final class Report {
        final int score;
        final String evidence;

        Report(int score, String evidence) {
            this.score = score;
            this.evidence = evidence;
        }
    }

    private CandidateScoring() {
    }

    static int score(CandidateFeatures features) {
        return scoreReport(features).score;
    }

    static Report scoreReport(CandidateFeatures f) {
        int score = 0;
        StringBuilder evidence = new StringBuilder();

        boolean primary = f.usingStrings.contains(STRING_PRIMARY);
        boolean secondary = f.usingStrings.contains(STRING_SECONDARY);
        if (primary && secondary) {
            score += 45;
            evidence.append("|strings:both");
        } else if (primary || secondary || f.usingStrings.contains(STRING_SPACING_METRIC)) {
            score += 15;
            evidence.append("|strings:one");
        }
        if (f.usingStrings.contains(STRING_SPACING)
                || f.usingStrings.contains(STRING_BRAND_SAFETY)) {
            score += 10;
            evidence.append("|spacingLogic");
        }
        if (f.shapeConflict) {
            score -= 25;
            evidence.append("|shapeConflict");
        } else if (f.cpsShape) {
            score += 15;
            evidence.append("|cpsShape");
        }
        if (f.flowOverride == XTargetVerifier.TriState.YES) {
            score += 10;
            evidence.append("|emitOverride");
        } else if (f.flowOverride == XTargetVerifier.TriState.NO) {
            score -= NO_INTERFACE_OVERRIDE_PENALTY;
            evidence.append("|noEmitOverride");
        }
        if (f.inUrtPackage) {
            score += 10;
            evidence.append("|urtPackage");
        }
        if ("emit".equals(f.methodName)) {
            score += 5;
            evidence.append("|nameSeed");
        }
        return new Report(score, evidence.length() == 0 ? "none" : evidence.substring(1));
    }

    /** True when the top two scores are too close to choose without a witness. */
    static boolean isAmbiguousTop(List<ScoredCandidate> ranked) {
        if (ranked == null || ranked.size() < 2) {
            return false;
        }
        return ranked.get(0).score - ranked.get(1).score < GAP_AMBIGUITY;
    }

    /**
     * Discovery early-exit (P1-1 performance rule): once an entry batch has
     * produced a single high-confidence, unambiguous leader, weaker discovery
     * entries cannot change the outcome and are skipped to keep the strong
     * cold-start path fast.
     */
    static boolean discoveryCanStop(List<ScoredCandidate> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return false;
        }
        return ranked.get(0).score >= ACCEPT_STATIC && !isAmbiguousTop(ranked);
    }

    static final class CandidateFeatures {
        final java.util.Set<String> usingStrings;
        final String methodName;
        final boolean cpsShape;
        final boolean shapeConflict;
        final XTargetVerifier.TriState flowOverride;
        final boolean inUrtPackage;

        CandidateFeatures(java.util.Set<String> usingStrings, String methodName,
                          boolean cpsShape, boolean shapeConflict,
                          XTargetVerifier.TriState flowOverride, boolean inUrtPackage) {
            this.usingStrings = usingStrings;
            this.methodName = methodName;
            this.cpsShape = cpsShape;
            this.shapeConflict = shapeConflict;
            this.flowOverride = flowOverride;
            this.inUrtPackage = inUrtPackage;
        }
    }

    static final class ScoredCandidate {
        final String classDescriptor;
        final String methodDescriptor;
        final int score;
        final String evidence;

        ScoredCandidate(String classDescriptor, String methodDescriptor,
                        int score, String evidence) {
            this.classDescriptor = classDescriptor;
            this.methodDescriptor = methodDescriptor;
            this.score = score;
            this.evidence = evidence;
        }
    }
}
