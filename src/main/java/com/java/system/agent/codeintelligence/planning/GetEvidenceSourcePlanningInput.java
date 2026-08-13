package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 精確 evidence source 規劃工具的模型輸入。 */
public record GetEvidenceSourcePlanningInput(
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true)
        @JsonPropertyDescription("Exact evidence identity; kind selects the required statement or mapper fragment variant.")
        @NotNull @Valid SemanticDtos.EvidenceSourceFollowUpIdentity identity) {
}
