package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;

import java.util.Objects;

/** 方法原始碼 capability 的 execution input */
public record GetMethodSourceExecutionInput(SemanticDtos.MethodTargetPayload target) {
    public GetMethodSourceExecutionInput {
        target = Objects.requireNonNull(target, "target is required");
    }
}
