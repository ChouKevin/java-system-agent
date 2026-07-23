package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.port.out.SemanticFailure;

import java.util.Objects;

public final class AttemptPreparationException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;
    private final SemanticFailure semanticFailure;

    public AttemptPreparationException(
            AttemptLifecycle lastCommittedLifecycle,
            SemanticFailure semanticFailure) {
        super("repository revision preparation is unavailable");
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
        Objects.requireNonNull(semanticFailure, "semantic failure must not be null");
        this.semanticFailure = new SemanticFailure(
                semanticFailure.code(), semanticFailure.message(), semanticFailure.retryable());
    }

    public AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }

    public SemanticFailure semanticFailure() {
        return semanticFailure;
    }
}
