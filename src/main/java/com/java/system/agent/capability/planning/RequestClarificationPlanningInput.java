package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * agent_request_clarification planning tool 的模型輸入
 */
public record RequestClarificationPlanningInput(
        @JsonProperty(required = true) @NotBlank String question,
        @JsonProperty(required = true) @NotNull List<@NotBlank String> candidateHandles,
        @JsonProperty(required = true) @NotBlank String reason) {
}
