package com.java.system.agent.runtime.application.goal;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link NoProgressPolicy#evaluate} 的回傳值：連續無進展的次數、是否已達終止門檻，
 * 以及達到門檻時附上的 {@link GoalBlockReason}
 *
 * <p>{@code terminate} 為真時必須攜帶 {@code blocker}，為假時一律不能攜帶，兩者互斥
 * 由建構檢查保證</p>
 */
public record NoProgressEvaluation(
        int consecutiveNoProgress,
        boolean terminate,
        Optional<GoalBlockReason> blocker) {

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
