package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.answering.domain.answer.StatementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * agent_submit_answer 的封閉 statement 模型輸入
 */
public sealed interface AnswerStatementPlanningInput permits AnswerStatementPlanningInput.Fact,
        AnswerStatementPlanningInput.Uncertainty, AnswerStatementPlanningInput.Limitation,
        AnswerStatementPlanningInput.Question {

    String statementId();

    @JsonIgnore
    StatementType type();

    String text();

    Set<String> citationHandles();

    Set<String> observationIds();

    record Fact(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotBlank String claimId,
            @JsonProperty(required = true) @NotEmpty @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        @Override
        public StatementType type() {
            return StatementType.FACT;
        }
    }

    record Uncertainty(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        @Override
        public StatementType type() {
            return StatementType.UNCERTAINTY;
        }
    }

    record Limitation(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        @Override
        public StatementType type() {
            return StatementType.LIMITATION;
        }
    }

    record Question(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        @Override
        public StatementType type() {
            return StatementType.QUESTION;
        }
    }
}
