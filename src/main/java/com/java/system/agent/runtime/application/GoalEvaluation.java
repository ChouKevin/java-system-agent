package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisOutcome;

import java.util.Objects;
import java.util.Optional;

public record GoalEvaluation(
        GoalEvaluationStatus status,
        Optional<AnalysisOutcome> outcome,
        String reason) {

    public GoalEvaluation {
        Objects.requireNonNull(status, "goal evaluation status must not be null");
        Objects.requireNonNull(outcome, "analysis outcome must not be null");
        Objects.requireNonNull(reason, "goal evaluation reason must not be null");
        reason = reason.trim();
        if (reason.isBlank()) {
            throw new IllegalArgumentException("goal evaluation reason must not be blank");
        }
        if (status == GoalEvaluationStatus.TERMINAL && !outcome.isPresent()) {
            throw new IllegalArgumentException("terminal goal evaluation requires an outcome");
        }
        if (status == GoalEvaluationStatus.CONTINUE && outcome.isPresent()) {
            throw new IllegalArgumentException("continuing goal evaluation cannot contain an outcome");
        }
    }
}
