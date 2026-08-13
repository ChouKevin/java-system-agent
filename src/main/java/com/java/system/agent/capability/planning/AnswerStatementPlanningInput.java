package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.system.agent.answering.domain.answer.StatementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;
import java.util.Set;

/**
 * agent_submit_answer 中由 type 判別的封閉 statement 模型輸入
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AnswerStatementPlanningInput.Fact.class, name = "FACT"),
        @JsonSubTypes.Type(value = AnswerStatementPlanningInput.Uncertainty.class, name = "UNCERTAINTY"),
        @JsonSubTypes.Type(value = AnswerStatementPlanningInput.Limitation.class, name = "LIMITATION"),
        @JsonSubTypes.Type(value = AnswerStatementPlanningInput.Question.class, name = "QUESTION")
})
public sealed interface AnswerStatementPlanningInput permits AnswerStatementPlanningInput.Fact,
        AnswerStatementPlanningInput.Uncertainty, AnswerStatementPlanningInput.Limitation,
        AnswerStatementPlanningInput.Question {

    String statementId();

    StatementType type();

    String text();

    Set<String> citationHandles();

    Set<String> observationIds();

    record Fact(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotNull StatementType type,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotBlank String claimId,
            @JsonProperty(required = true) @NotEmpty @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        public Fact {
            type = exact(type, StatementType.FACT);
        }
    }

    record Uncertainty(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotNull StatementType type,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        public Uncertainty {
            type = exact(type, StatementType.UNCERTAINTY);
        }
    }

    record Limitation(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotNull StatementType type,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        public Limitation {
            type = exact(type, StatementType.LIMITATION);
        }
    }

    record Question(
            @JsonProperty(required = true) @NotBlank String statementId,
            @JsonProperty(required = true) @NotNull StatementType type,
            @JsonProperty(required = true) @NotBlank String text,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> citationHandles,
            @JsonProperty(required = true) @NotNull Set<@NotBlank String> observationIds)
            implements AnswerStatementPlanningInput {

        public Question {
            type = exact(type, StatementType.QUESTION);
        }
    }

    private static StatementType exact(StatementType value, StatementType expected) {
        StatementType required = Objects.requireNonNull(value, "statement type is required");
        if (required != expected) {
            throw new IllegalArgumentException("unexpected statement type");
        }
        return required;
    }
}
