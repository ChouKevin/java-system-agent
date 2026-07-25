package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisState;

import java.util.Objects;

final class SemanticResultHandlingException extends RuntimeException {

    private final AnalysisState lastCommittedState;

    SemanticResultHandlingException(
            AnalysisState lastCommittedState,
            AnalysisTransitionCommitException cause) {
        super("semantic result transition could not be committed", cause);
        this.lastCommittedState = Objects.requireNonNull(
                lastCommittedState, "last committed analysis state must not be null");
    }

    AnalysisState lastCommittedState() {
        return lastCommittedState;
    }
}
