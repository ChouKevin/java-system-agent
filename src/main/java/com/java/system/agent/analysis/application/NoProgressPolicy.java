package com.java.system.agent.analysis.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NoProgressPolicy {

    private final int limit;

    public NoProgressPolicy(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("no-progress limit must be positive");
        }
        this.limit = limit;
    }

    public NoProgressEvaluation evaluate(List<ProgressFingerprint> history) {
        Objects.requireNonNull(history, "progress fingerprint history must not be null");
        if (history.size() < 1) {
            return new NoProgressEvaluation(0, false, Optional.empty());
        }
        ProgressFingerprint latest = Objects.requireNonNull(
                history.getLast(), "progress fingerprint must not be null");
        int consecutive = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            ProgressFingerprint fingerprint = Objects.requireNonNull(
                    history.get(index), "progress fingerprint must not be null");
            if (!latest.equals(fingerprint)) {
                break;
            }
            consecutive++;
        }
        boolean terminate = consecutive >= limit;
        return new NoProgressEvaluation(
                consecutive,
                terminate,
                terminate ? Optional.of(GoalBlocker.NO_PROGRESS) : Optional.empty());
    }
}
