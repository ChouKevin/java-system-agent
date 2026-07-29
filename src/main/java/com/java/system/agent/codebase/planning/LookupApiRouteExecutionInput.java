package com.java.system.agent.codebase.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;

/**
 * lookup-api-route executor 的 capability 專屬輸入
 */
public record LookupApiRouteExecutionInput(
        @NotBlank String apiPath,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.FAIL) String httpMethod) {
}
