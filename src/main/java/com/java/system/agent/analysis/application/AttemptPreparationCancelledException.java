package com.java.system.agent.analysis.application;

import java.util.Objects;

final class AttemptPreparationCancelledException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;

    AttemptPreparationCancelledException(AttemptLifecycle lastCommittedLifecycle) {
        super("analysis cancellation was requested during revision preparation");
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
    }

    AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }
}
