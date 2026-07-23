package com.java.system.agent.analysis.port.in;

import com.java.system.agent.analysis.domain.AnalysisRun;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;

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
    }

    private static boolean isTerminal(AnalysisStatus status) {
        return switch (status) {
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> true;
            case RECEIVED, UNDERSTANDING, SCOPE_RESOLVING, REVISION_PINNING,
                    PLANNING, EXECUTING, COMPOSING, VERIFYING -> false;
        };
    }
}
