package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;

import java.util.Objects;
import java.util.Optional;

/** 方法實作探索的 execution input，follow-up 可提供精確目標 */
public record DiscoverMethodImplementationsExecutionInput(Optional<SemanticDtos.MethodTargetPayload> boundTarget) {
    public DiscoverMethodImplementationsExecutionInput {
        boundTarget = Objects.requireNonNull(boundTarget, "bound target is required");
    }
}
