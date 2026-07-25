package com.java.system.agent.runtime.application;

import java.util.Objects;

final class AttemptPreparationBudgetExhaustedException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;

    AttemptPreparationBudgetExhaustedException(AttemptLifecycle lastCommittedLifecycle) {
        super("analysis step budget is exhausted during revision preparation");
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
    }

    AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }
}
