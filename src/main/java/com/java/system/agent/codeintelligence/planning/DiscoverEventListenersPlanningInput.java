package com.java.system.agent.codeintelligence.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** 事件監聽器探索規劃工具的模型輸入。 */
public record DiscoverEventListenersPlanningInput(
        @JsonProperty(required = true) @NotBlank String questionToResolve,
        @JsonProperty(required = true) @NotBlank String rationale,
        @JsonProperty(required = true) @NotBlank String eventType,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(0) Integer offset,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) @Min(1) @Max(100) Integer limit) {
}
