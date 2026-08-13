package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Optional;

/** 來源符號解析規劃工具的模型輸入。 */
public record ResolveSourceSymbolPlanningInput(
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @NotNull @Valid SemanticDtos.SourceSymbolContextPayload context,
        @JsonProperty(required = true) @NotBlank String symbol,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Valid Optional<SemanticDtos.Position> position) {

    public ResolveSourceSymbolPlanningInput {
        position = Optional.ofNullable(position).orElse(Optional.empty());
    }
}
