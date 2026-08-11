package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * agent_plan_question 產生依序業務資訊需求的模型輸入
 */
public record PlanQuestionPlanningInput(
        @JsonProperty(required = true)
        @NotNull @Size(min = 1, max = 12)
        List<@NotNull @Valid InformationNeedPlanningInput> needs) {
}
