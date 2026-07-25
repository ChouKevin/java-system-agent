package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;

import java.util.Objects;

public record AnalysisExecutionResult(
        AnalysisRun run,
        AttemptState finalState,
        AnalysisTerminationReason reason) {

    public AnalysisExecutionResult {
        Objects.requireNonNull(run, "analysis run must not be null");
        Objects.requireNonNull(finalState, "final analysis state must not be null");
        Objects.requireNonNull(reason, "analysis termination reason must not be null");
        if (!run.outcome().isPresent()) {
            throw new IllegalArgumentException("analysis run must be concluded");
        }
        if (!isTerminal(finalState.status())) {
            throw new IllegalArgumentException("final analysis state must be terminal");
        }
        if (!run.id().equals(finalState.runId())) {
            throw new IllegalArgumentException("final analysis state must belong to the analysis run");
        }
        if (!run.currentAttempt().id().equals(finalState.attemptId())) {
            throw new IllegalArgumentException("final analysis state must belong to the current analysis attempt");
        }
        if (!run.currentAttempt().revisionVector().equals(finalState.revisionVector())) {
            throw new IllegalArgumentException("final analysis state revision vector must match the current attempt");
        }
        if (!run.currentAttempt().budget().equals(finalState.budget())) {
            throw new IllegalArgumentException("final analysis state budget must match the current attempt");
        }
        AttemptOutcome attemptOutcome = run.currentAttempt().outcome()
                .orElseThrow(() -> new IllegalArgumentException("current analysis attempt must be concluded"));
        if (!isAttemptOutcomeCompatibleWithStatus(attemptOutcome, finalState.status())) {
            throw new IllegalArgumentException("current analysis attempt outcome must match the final state status");
        }
        RunOutcome runOutcome = run.outcome()
                .orElseThrow(() -> new IllegalArgumentException("analysis run must be concluded"));
        if (!isRunOutcomeCompatibleWithStatus(runOutcome, finalState.status())) {
            throw new IllegalArgumentException("analysis run outcome must match the final state status");
        }
        if (!isReasonCompatibleWithResult(reason, runOutcome, finalState.status())) {
            throw new IllegalArgumentException("analysis termination reason must match the terminal result");
        }
    }

    private static boolean isTerminal(AttemptStatus status) {
        return switch (status) {
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> true;
            case RECEIVED, REVISION_PINNING, PLANNING, EXECUTING -> false;
        };
    }

    private static boolean isAttemptOutcomeCompatibleWithStatus(
            AttemptOutcome outcome,
            AttemptStatus status) {
        return switch (outcome) {
            case COMPLETED -> status == AttemptStatus.COMPLETED;
            case STALE -> status == AttemptStatus.STALE;
            case INCONCLUSIVE -> status == AttemptStatus.INCONCLUSIVE;
            case FAILED -> status == AttemptStatus.FAILED;
            case CANCELLED -> status == AttemptStatus.CANCELLED;
        };
    }

    private static boolean isRunOutcomeCompatibleWithStatus(
            RunOutcome outcome,
            AttemptStatus status) {
        return switch (outcome) {
            case COMPLETED -> status == AttemptStatus.COMPLETED;
            case INCONCLUSIVE -> status == AttemptStatus.INCONCLUSIVE || status == AttemptStatus.STALE;
            case FAILED -> status == AttemptStatus.FAILED;
            case CANCELLED -> status == AttemptStatus.CANCELLED;
        };
    }

    private static boolean isReasonCompatibleWithResult(
            AnalysisTerminationReason reason,
            RunOutcome outcome,
            AttemptStatus status) {
        return switch (reason) {
            case GOAL_COMPLETED -> outcome == RunOutcome.COMPLETED
                    && status == AttemptStatus.COMPLETED;
            case CAPABILITY_MISSING, PREREQUISITE_MISSING, SEMANTIC_AMBIGUOUS,
                    SEMANTIC_FORBIDDEN, BUDGET_EXHAUSTED, NO_PROGRESS -> outcome == RunOutcome.INCONCLUSIVE
                    && status == AttemptStatus.INCONCLUSIVE;
            case SEMANTIC_UNAVAILABLE -> (outcome == RunOutcome.INCONCLUSIVE
                    && status == AttemptStatus.INCONCLUSIVE)
                    || (outcome == RunOutcome.FAILED
                    && status == AttemptStatus.FAILED);
            case REVISION_RESTART_LIMIT -> outcome == RunOutcome.INCONCLUSIVE
                    && (status == AttemptStatus.STALE || status == AttemptStatus.INCONCLUSIVE);
            case CANCELLED -> outcome == RunOutcome.CANCELLED
                    && status == AttemptStatus.CANCELLED;
            case RUNTIME_FAILURE -> outcome == RunOutcome.FAILED
                    && status == AttemptStatus.FAILED;
        };
    }
}
