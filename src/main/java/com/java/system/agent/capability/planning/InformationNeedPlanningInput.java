package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * agent_plan_question 單一業務資訊需求的模型輸入
 */
public record InformationNeedPlanningInput(
        @JsonProperty(required = true) @NotBlank @Size(max = 32) String id,
        @JsonProperty(required = true) @NotBlank @Size(max = 500) String description) {
}
