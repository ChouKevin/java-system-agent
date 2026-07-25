package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.AnalysisRun;
import com.java.system.agent.runtime.domain.AnalysisState;
import com.java.system.agent.runtime.domain.AnalysisStatus;
import com.java.system.agent.runtime.domain.AnalysisOutcome;
import com.java.system.agent.runtime.domain.AttemptOutcome;

import java.util.Objects;

public record AnalysisExecutionResult(
        AnalysisRun run,
        AnalysisState finalState,
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
        AnalysisOutcome runOutcome = run.outcome()
                .orElseThrow(() -> new IllegalArgumentException("analysis run must be concluded"));
        if (!isRunOutcomeCompatibleWithStatus(runOutcome, finalState.status())) {
            throw new IllegalArgumentException("analysis run outcome must match the final state status");
        }
        if (!isReasonCompatibleWithResult(reason, runOutcome, finalState.status())) {
            throw new IllegalArgumentException("analysis termination reason must match the terminal result");
        }
    }

    private static boolean isTerminal(AnalysisStatus status) {
        return switch (status) {
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> true;
            case RECEIVED, UNDERSTANDING, SCOPE_RESOLVING, REVISION_PINNING,
                    PLANNING, EXECUTING, COMPOSING, VERIFYING -> false;
        };
    }

    private static boolean isAttemptOutcomeCompatibleWithStatus(
            AttemptOutcome outcome,
            AnalysisStatus status) {
        return switch (outcome) {
            case COMPLETED -> status == AnalysisStatus.COMPLETED;
            case STALE -> status == AnalysisStatus.STALE;
            case INCONCLUSIVE -> status == AnalysisStatus.INCONCLUSIVE;
            case FAILED -> status == AnalysisStatus.FAILED;
            case CANCELLED -> status == AnalysisStatus.CANCELLED;
        };
    }

    private static boolean isRunOutcomeCompatibleWithStatus(
            AnalysisOutcome outcome,
            AnalysisStatus status) {
        return switch (outcome) {
            case COMPLETED -> status == AnalysisStatus.COMPLETED;
            case INCONCLUSIVE -> status == AnalysisStatus.INCONCLUSIVE || status == AnalysisStatus.STALE;
            case FAILED -> status == AnalysisStatus.FAILED;
            case CANCELLED -> status == AnalysisStatus.CANCELLED;
        };
    }

    private static boolean isReasonCompatibleWithResult(
            AnalysisTerminationReason reason,
            AnalysisOutcome outcome,
            AnalysisStatus status) {
        return switch (reason) {
            case GOAL_COMPLETED -> outcome == AnalysisOutcome.COMPLETED
                    && status == AnalysisStatus.COMPLETED;
            case CAPABILITY_MISSING, PREREQUISITE_MISSING, SEMANTIC_AMBIGUOUS,
                    SEMANTIC_FORBIDDEN, BUDGET_EXHAUSTED, NO_PROGRESS -> outcome == AnalysisOutcome.INCONCLUSIVE
                    && status == AnalysisStatus.INCONCLUSIVE;
            case SEMANTIC_UNAVAILABLE -> (outcome == AnalysisOutcome.INCONCLUSIVE
                    && status == AnalysisStatus.INCONCLUSIVE)
                    || (outcome == AnalysisOutcome.FAILED
                    && status == AnalysisStatus.FAILED);
            case REVISION_RESTART_LIMIT -> outcome == AnalysisOutcome.INCONCLUSIVE
                    && (status == AnalysisStatus.STALE || status == AnalysisStatus.INCONCLUSIVE);
            case CANCELLED -> outcome == AnalysisOutcome.CANCELLED
                    && status == AnalysisStatus.CANCELLED;
            case RUNTIME_FAILURE -> outcome == AnalysisOutcome.FAILED
                    && status == AnalysisStatus.FAILED;
        };
    }
}
