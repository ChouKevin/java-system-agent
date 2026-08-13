package com.java.system.agent.capability.planning;

import java.util.Objects;

/**
 * 一個目前可供模型規劃的 tool。
 */
public record IssuedPlanningTool(String name) {

    public IssuedPlanningTool {
        Objects.requireNonNull(name, "issued planning tool name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("issued planning tool name must not be blank");
        }
    }
}
