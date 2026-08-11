package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * agent_submit_answer planning tool 的模型輸入
 */
public record SubmitAnswerPlanningInput(
        @JsonProperty(required = true) @NotEmpty @NotNull List<@NotNull @Valid AnswerStatementPlanningInput> statements,
        @JsonProperty(required = true) @NotEmpty @NotNull List<@NotNull @Valid NeedResolutionPlanningInput> resolutions) {
}
