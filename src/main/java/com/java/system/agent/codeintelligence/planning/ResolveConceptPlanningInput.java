package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 精確概念解析規劃工具的模型輸入。 */
public record ResolveConceptPlanningInput(
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact concept identity; kind selects the required typed identity variant.")
        @NotNull @Valid SemanticPlanningIdentities.Concept identity) {
}
