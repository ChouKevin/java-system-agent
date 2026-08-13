package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Incoming call graph 規劃工具的模型輸入。 */
public record IncomingCallGraphPlanningInput(
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @NotNull @Valid SemanticDtos.MethodTargetPayload target,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(1) @Max(2) Integer depth) {
}
