package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisRun;
import com.java.system.agent.runtime.domain.AnalysisState;

import java.util.Objects;

public record AttemptLifecycle(
        AnalysisRun run,
        AnalysisState state,
        int revisionRestartCount) {

    public AttemptLifecycle {
        Objects.requireNonNull(run, "analysis run must not be null");
        Objects.requireNonNull(state, "analysis state must not be null");
        if (revisionRestartCount < 0 || revisionRestartCount > 1) {
            throw new IllegalArgumentException("revision restart count must be between zero and one");
        }
        if (!run.id().equals(state.runId())) {
            throw new IllegalArgumentException("analysis run and state must belong to the same run");
        }
        if (!run.currentAttempt().id().equals(state.attemptId())) {
            throw new IllegalArgumentException("analysis run current attempt and state must match");
        }
    }
}
