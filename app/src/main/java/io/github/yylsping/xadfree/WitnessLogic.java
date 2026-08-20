package io.github.yylsping.xadfree;

import java.util.List;

/**
 * Pure runtime-witness decision tables. The inline hook witness uses
 * {@link #onInvocation}; the probe witness accumulates per-candidate evidence
 * across invocations and decides through {@link ProbeEvidence}. Inputs
 * describe observed invocations only — no tweet content is ever retained.
 */
final class WitnessLogic {
    static final int FAILURE_LIMIT = 3;

    /** Probe confirmation: at least this many non-empty shaped invocations. */
    static final int MIN_CONFIRM_INVOCATIONS = 2;
    /** Probe confirmation: shaped elements must be at least this fraction. */
    static final double MIN_SHAPED_RATIO = 0.5;
    /** Probe confirmation: elements inspected per invocation (cap, not exact). */
    static final int PER_INVOCATION_SAMPLE_CAP = 16;

    enum Decision {
        /** No evidence either way (e.g. empty list): keep observing. */
        KEEP_WAITING,
        /** Real business shape proven. */
        PASSED,
        /** Negative evidence (non-list argument or unshaped non-empty sample). */
        STRIKE
    }

    private WitnessLogic() {
    }

    static Decision onInvocation(boolean argIsList, List<?> sample, boolean shapedFound) {
        if (!argIsList) {
            return Decision.STRIKE;
        }
        if (shapedFound) {
            return Decision.PASSED;
        }
        // An empty list carries no evidence; a non-empty unshaped one does.
        return sample == null || sample.isEmpty() ? Decision.KEEP_WAITING : Decision.STRIKE;
    }

    static boolean limitReached(int strikes) {
        return strikes >= FAILURE_LIMIT;
    }

    /**
     * Accumulated probe evidence across invocations of one candidate. A single
     * weak sample can never confirm a candidate (P1-8): confirmation needs
     * several real invocations whose inspected elements are predominantly
     * URT-entry shaped.
     */
    static final class ProbeEvidence {
        int invocations;
        int nonEmptyInvocations;
        int sampledElements;
        int shapedElements;
        int adEvidenceSamples;
        int strikes;

        void record(boolean argIsList, List<?> sample, int sampled, int shaped,
                    boolean adEvidence) {
            invocations++;
            if (!argIsList) {
                strikes++;
                return;
            }
            if (sample == null || sample.isEmpty()) {
                return;
            }
            nonEmptyInvocations++;
            sampledElements += sampled;
            shapedElements += shaped;
            if (adEvidence) {
                adEvidenceSamples++;
            }
            if (shaped == 0) {
                strikes++;
            }
        }

        boolean strikeLimitReached() {
            return strikes >= FAILURE_LIMIT;
        }

        boolean confirmed() {
            return nonEmptyInvocations >= MIN_CONFIRM_INVOCATIONS
                    && shapedRatio() >= MIN_SHAPED_RATIO;
        }

        /** LIKELY: one strong invocation seen; not enough to promote yet. */
        boolean likely() {
            return nonEmptyInvocations >= 1 && shapedRatio() >= MIN_SHAPED_RATIO;
        }

        double shapedRatio() {
            return sampledElements == 0 ? 0.0 : (double) shapedElements / sampledElements;
        }

        String describe() {
            return "invocations=" + invocations
                    + " nonEmpty=" + nonEmptyInvocations
                    + " sampled=" + sampledElements
                    + " shaped=" + shapedElements
                    + " shapedRatio=" + String.format(java.util.Locale.ROOT, "%.2f", shapedRatio())
                    + " adSamples=" + adEvidenceSamples
                    + " strikes=" + strikes;
        }

        static int sampleCap(int size) {
            return Math.min(size, PER_INVOCATION_SAMPLE_CAP);
        }
    }
}
