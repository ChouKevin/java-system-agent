package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.RunOutcome;

/**
 * lifecycle 是否仍處於可套用主動操作的狀態，以及 attempt／run 收斂結果是否相容的檢查
 *
 * <p>由 {@link AttemptLifecycleManager} 在收斂、重啟與釘選發現的 repository 前呼叫，
 * 確保這些操作不會套用到已經終結的 lifecycle 或產生互相矛盾的收斂結果</p>
 */
final class AttemptConclusionValidator {

    private AttemptConclusionValidator() {
    }

    public static void validateActive(AttemptLifecycle lifecycle) {
        if (lifecycle.run().outcome().isPresent()
                || lifecycle.run().currentAttempt().outcome().isPresent()
                || !isActiveStatus(lifecycle.state().status())) {
            throw new IllegalArgumentException(
                    "analysis operation requires an active lifecycle before applying an active operation");
        }
    }

    private static boolean isActiveStatus(AttemptStatus status) {
        return switch (status) {
            case RECEIVED, REVISION_PINNING, PLANNING, EXECUTING -> true;
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> false;
        };
    }

    public static void validateCompatibleOutcomes(
            AttemptOutcome attemptOutcome,
            RunOutcome analysisOutcome) {
        boolean compatible = switch (attemptOutcome) {
            case COMPLETED -> analysisOutcome == RunOutcome.COMPLETED;
            case INCONCLUSIVE, STALE -> analysisOutcome == RunOutcome.INCONCLUSIVE;
            case FAILED -> analysisOutcome == RunOutcome.FAILED;
            case CANCELLED -> analysisOutcome == RunOutcome.CANCELLED;
        };
        if (!compatible) {
            throw new IllegalArgumentException("analysis attempt and run outcomes are not compatible");
        }
    }
}
