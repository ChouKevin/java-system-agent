package com.java.system.agent.answering.domain.plan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 回答問題前必須依序解析的資訊需求計畫
 */
public record QuestionPlan(List<InformationNeed> needs) {

    public static final int MAX_NEEDS = 12;

    public QuestionPlan {
        Objects.requireNonNull(needs, "question plan needs must not be null");
        needs = List.copyOf(needs);
        if (needs.isEmpty()) {
            throw new IllegalArgumentException("question plan must contain at least one information need");
        }
        if (needs.size() > MAX_NEEDS) {
            throw new IllegalArgumentException("question plan exceeds the maximum number of information needs");
        }
        Set<InformationNeedId> identifiers = new LinkedHashSet<>();
        for (InformationNeed need : needs) {
            Objects.requireNonNull(need, "question plan information need must not be null");
            if (!identifiers.add(need.id())) {
                throw new IllegalArgumentException("question plan information need IDs must be unique");
            }
        }
    }
}
