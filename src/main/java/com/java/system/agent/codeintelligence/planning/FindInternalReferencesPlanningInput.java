package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.capability.planning.CandidateBoundPlanningInput;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 僅允許調整 provider internal reference follow-up 上限的模型輸入。 */
public record FindInternalReferencesPlanningInput(
        @JsonProperty(required = true) @NotEmpty @Size(max = 1)
        List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP)
        @Min(1) @Max(100) Integer limit)
        implements CandidateBoundPlanningInput {
}
