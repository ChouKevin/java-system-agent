package com.java.system.agent.runtime.domain.need;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次 Attempt 要達成的目標
 *
 * <p>{@code requiredNeedIds} 就是 {@code DefaultGoalEvaluator} 判斷完成與否所檢查的集合——
 * 全部都已解析且有對應證據時，Attempt 才算達成目標</p>
 */
public record Goal(String description, Set<InformationNeedId> requiredNeedIds) {

    public Goal {
        Objects.requireNonNull(description, "goal description must not be null");
        Objects.requireNonNull(requiredNeedIds, "required information need IDs must not be null");
        description = description.trim();
        if (description.isBlank()) {
            throw new IllegalArgumentException("goal description must not be blank");
        }
        requiredNeedIds = Collections.unmodifiableSet(new TreeSet<>(requiredNeedIds));
    }
}
