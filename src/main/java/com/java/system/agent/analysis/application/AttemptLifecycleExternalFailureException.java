package com.java.system.agent.analysis.application;

import java.util.Objects;

public final class AttemptLifecycleExternalFailureException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;

    public AttemptLifecycleExternalFailureException(
            String message,
            AttemptLifecycle lastCommittedLifecycle,
            RuntimeException cause) {
        super(message, Objects.requireNonNull(cause, "external failure cause must not be null"));
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
    }

    public AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }
}
