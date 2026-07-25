package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.run.AttemptState;

import java.util.Objects;

public final class AnalysisExecutionException extends RuntimeException {

    private static final String MESSAGE = "analysis execution stopped before a terminal state could be committed";

    private final AnalysisTerminationReason reason;
    private final AttemptState lastCommittedState;

    public AnalysisExecutionException(
            AnalysisTerminationReason reason,
            AttemptState lastCommittedState,
            Throwable cause) {
        super(MESSAGE, cause);
        this.reason = Objects.requireNonNull(reason, "analysis termination reason must not be null");
        this.lastCommittedState = Objects.requireNonNull(
                lastCommittedState,
                "last committed analysis state must not be null");
    }

    public AnalysisTerminationReason reason() {
        return reason;
    }

    public AttemptState lastCommittedState() {
        return lastCommittedState;
    }
}
