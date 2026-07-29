package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * incoming-call-graph 規劃工具的模型輸入，缺省 depth 正規化為 2
 */
public record IncomingCallGraphPlanningInput(
        @JsonProperty(required = true) @NotEmpty @NotNull List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(1) @Max(2) Integer depth) {
}
