package com.java.system.agent.answering.domain.plan;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * QuestionPlan 中資訊需求的穩定識別碼
 */
public record InformationNeedId(String value) {

    private static final Pattern VALUE_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");

    public InformationNeedId {
        Objects.requireNonNull(value, "information need ID must not be null");
        if (!VALUE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("information need ID must match the required pattern");
        }
    }
}
