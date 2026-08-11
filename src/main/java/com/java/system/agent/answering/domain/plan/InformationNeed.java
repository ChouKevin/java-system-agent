package com.java.system.agent.answering.domain.plan;

import java.util.Objects;

/**
 * QuestionPlan 必須解析的一項資訊需求
 */
public record InformationNeed(InformationNeedId id, String description) {

    public static final int MAX_DESCRIPTION_LENGTH = 500;

    public InformationNeed {
        Objects.requireNonNull(id, "information need ID must not be null");
        Objects.requireNonNull(description, "information need description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("information need description must not be blank");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("information need description exceeds the maximum length");
        }
    }
}
