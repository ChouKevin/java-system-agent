package com.java.system.agent.analysis.application;

import java.util.Objects;
import java.util.Optional;

public record NoProgressEvaluation(
        int consecutiveNoProgress,
        boolean terminate,
        Optional<GoalBlocker> blocker) {

    public NoProgressEvaluation {
        Objects.requireNonNull(blocker, "no-progress blocker must not be null");
        if (consecutiveNoProgress < 0) {
            throw new IllegalArgumentException("consecutive no-progress count must not be negative");
        }
        if (terminate && !blocker.isPresent()) {
            throw new IllegalArgumentException("terminal no-progress evaluation requires a blocker");
        }
        if (!terminate && blocker.isPresent()) {
            throw new IllegalArgumentException("continuing no-progress evaluation cannot contain a blocker");
        }
    }
}
