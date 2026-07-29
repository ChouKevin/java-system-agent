package com.java.system.agent.codebase.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * suggest-api-route executor 的 capability 專屬輸入
 */
public record SuggestApiRouteExecutionInput(
        @NotBlank String apiPath,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String httpMethod,
        @Min(1) @Max(20) int limit) {
}
