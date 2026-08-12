package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.capability.planning.CandidateBoundPlanningInput;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Optional;

/** 來源符號解析規劃工具的模型輸入 */
public record ResolveSourceSymbolPlanningInput(
        @JsonProperty(required = true) @NotEmpty @Size(max = 1) List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @NotBlank String symbol,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Valid Optional<SemanticDtos.Position> position)
        implements CandidateBoundPlanningInput {
}
