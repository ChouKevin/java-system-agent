package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.java.system.agent.answering.domain.answer.StatementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * agent_submit_answer 中單一 statement 的 provider-neutral 模型輸入
 */
public record AnswerStatementPlanningInput(
        @JsonProperty(required = true) @NotBlank String statementId,
        @JsonProperty(required = true) @NotNull StatementType type,
        @JsonProperty(required = true) @NotBlank String text,
        @JsonProperty(required = false) @JsonSetter(nulls = Nulls.SKIP) String claimId,
        @JsonProperty(required = true) @NotNull Set<@NotBlank String> citationHandles,
        @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds) {
}
