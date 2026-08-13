package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;

import java.util.Objects;

/** 方法實作探索的 execution input */
public record DiscoverMethodImplementationsExecutionInput(SemanticDtos.MethodTargetPayload target) {
    public DiscoverMethodImplementationsExecutionInput {
        target = Objects.requireNonNull(target, "target is required");
    }
}
