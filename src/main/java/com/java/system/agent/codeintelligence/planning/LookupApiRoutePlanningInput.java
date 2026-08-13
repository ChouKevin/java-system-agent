package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;

/** API route lookup 規劃工具的模型輸入。 */
public record LookupApiRoutePlanningInput(
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @NotBlank String apiPath,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String httpMethod) {
}
