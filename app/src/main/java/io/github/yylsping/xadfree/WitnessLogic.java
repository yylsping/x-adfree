package io.github.yylsping.xadfree;

import java.util.List;

/**
 * Pure runtime-witness decision table shared by the inline hook witness and
 * the probe witness. Inputs describe one observed invocation; the caller owns
 * strike counting and teardown.
 */
final class WitnessLogic {
    static final int FAILURE_LIMIT = 3;

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
}
