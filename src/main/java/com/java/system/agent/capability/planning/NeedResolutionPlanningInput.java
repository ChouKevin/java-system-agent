package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.answering.domain.plan.NeedResolutionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * agent_submit_answer 中每個已規劃資訊需求的解析輸入
 */
public record NeedResolutionPlanningInput(
        @JsonProperty(required = true) @NotBlank @Size(max = 32) String needId,
        @JsonProperty(required = true) @NotNull NeedResolutionStatus status,
        @JsonProperty(required = true) @NotNull List<@NotBlank String> evidenceHandles,
        @JsonProperty(required = true) @NotNull List<@NotBlank String> observationIds) {
}
