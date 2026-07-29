package com.java.system.agent.codebase.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * suggest-api-route 規劃工具的模型輸入
 */
public record SuggestApiRoutePlanningInput(
        @JsonProperty(required = true) @NotNull List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @NotBlank String apiPath,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String httpMethod,
        @JsonProperty(required = true) @Min(1) @Max(20) int limit) {
}
