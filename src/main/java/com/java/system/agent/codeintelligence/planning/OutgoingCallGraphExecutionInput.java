package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Objects;

/**
 * outgoing-call-graph executor 的 capability 專屬輸入
 */
public record OutgoingCallGraphExecutionInput(@Min(1) @Max(2) int depth,
                                              SemanticDtos.MethodTargetPayload target) {
    public OutgoingCallGraphExecutionInput {
        target = Objects.requireNonNull(target, "target is required");
    }
}
