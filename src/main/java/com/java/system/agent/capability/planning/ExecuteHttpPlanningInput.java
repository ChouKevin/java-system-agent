package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * execute_http planning tool 的嚴格輸入契約
 */
public record ExecuteHttpPlanningInput(
        @JsonProperty(required = true) @NotNull ExternalHttpMethod method,
        @JsonProperty(required = true) @NotBlank String targetUrl,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String jsonBody,
        @JsonProperty(required = true) @NotBlank String rationale) {
}
