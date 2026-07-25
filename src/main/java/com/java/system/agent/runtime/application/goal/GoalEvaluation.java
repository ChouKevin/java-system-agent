package com.java.system.agent.runtime.application.goal;

import com.java.system.agent.runtime.domain.run.RunOutcome;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link GoalEvaluator#evaluate} 的回傳值：是否終止、若終止則附上的結果，以及診斷訊息
 *
 * <p>{@code TERMINAL} 必須攜帶 {@code outcome}，{@code CONTINUE} 一律不能攜帶，兩者
 * 互斥由建構檢查保證</p>
 */
public record GoalEvaluation(
        GoalEvaluationStatus status,
        Optional<RunOutcome> outcome,
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
