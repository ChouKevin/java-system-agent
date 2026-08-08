package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Objects;
import java.util.Optional;

/**
 * incoming-call-graph executor 的 capability 專屬輸入
 */
public record IncomingCallGraphExecutionInput(@Min(1) @Max(2) int depth,
                                              Optional<SemanticDtos.MethodTargetPayload> boundTarget) {
    public IncomingCallGraphExecutionInput {
        boundTarget = Objects.requireNonNull(boundTarget, "bound target is required");
    }

    public IncomingCallGraphExecutionInput(int depth) {
        this(depth, Optional.empty());
    }
}
