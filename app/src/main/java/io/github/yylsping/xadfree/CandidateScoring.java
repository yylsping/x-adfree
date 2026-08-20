package io.github.yylsping.xadfree;

import java.util.List;

/**
 * Orthogonal-feature scoring for urt_emit candidates. No single feature is
 * sufficient: the accepted threshold requires the high-entropy business
 * strings or several independent structural hits.
 *
 * <pre>
 * +45 both high-entropy business strings ("Ad removal: " + spacing log tail)
 * +15 one high-entropy business string
 * +15 exact (Object, Continuation) -&gt; Object CPS shape
 * +10 declaring class implements kotlinx.coroutines.flow.h (FlowCollector)
 * +10 spacing/brand-safety logic constants present
 * +10 declaring class under com.x.repositories.urt (bonus only, never required)
 *  +5 method still named "emit" (historical seed token)
 * -25 shape conflict (static/abstract or wrong parameter/return types)
 * </pre>
 *
 * ACCEPT_STATIC >= 70; ACCEPT_WITH_WITNESS >= 50; below that the candidate
 * only participates in runtime-witness probes.
 */
final class CandidateScoring {
    static final String STRING_PRIMARY = "Ad removal: ";
    static final String STRING_SECONDARY = " ads removed (spacing=";
    static final String STRING_SPACING_METRIC = "minimum_spacing_ad_removal";
    static final String STRING_SPACING = "minimum_spacing";

    static final int ACCEPT_STATIC = 70;
    static final int ACCEPT_WITH_WITNESS = 50;
    static final int GAP_AMBIGUITY = 10;

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
                || f.usingStrings.contains("brand_safety")) {
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
        if (f.declaresFlowCollector) {
            score += 10;
            evidence.append("|flowCollector");
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

    static final class CandidateFeatures {
        final java.util.Set<String> usingStrings;
        final String methodName;
        final boolean cpsShape;
        final boolean shapeConflict;
        final boolean declaresFlowCollector;
        final boolean inUrtPackage;

        CandidateFeatures(java.util.Set<String> usingStrings, String methodName,
                          boolean cpsShape, boolean shapeConflict,
                          boolean declaresFlowCollector, boolean inUrtPackage) {
            this.usingStrings = usingStrings;
            this.methodName = methodName;
            this.cpsShape = cpsShape;
            this.shapeConflict = shapeConflict;
            this.declaresFlowCollector = declaresFlowCollector;
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
