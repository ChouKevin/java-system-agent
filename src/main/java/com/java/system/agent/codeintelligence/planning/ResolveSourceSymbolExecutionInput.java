package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Objects;
import java.util.Optional;

/** 來源符號解析的 execution input，follow-up 可提供精確 context */
public record ResolveSourceSymbolExecutionInput(
        @NotBlank String symbol,
        @Valid Optional<SemanticDtos.Position> position,
        @Valid Optional<SemanticDtos.SourceSymbolContextPayload> boundContext) {
    public ResolveSourceSymbolExecutionInput {
        position = Objects.requireNonNull(position, "position is required");
        boundContext = Objects.requireNonNull(boundContext, "bound context is required");
    }
}
