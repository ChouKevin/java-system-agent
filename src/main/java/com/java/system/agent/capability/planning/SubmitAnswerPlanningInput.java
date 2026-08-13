package com.java.system.agent.capability.planning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.capability.planning.AnswerStatementPlanningInput.Fact;
import com.java.system.agent.capability.planning.AnswerStatementPlanningInput.Limitation;
import com.java.system.agent.capability.planning.AnswerStatementPlanningInput.Question;
import com.java.system.agent.capability.planning.AnswerStatementPlanningInput.Uncertainty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * agent_submit_answer planning tool 的模型輸入
 */
public record SubmitAnswerPlanningInput(
        @JsonProperty(required = true) @NotNull List<@NotNull @Valid Fact> facts,
        @JsonProperty(required = true) @NotNull List<@NotNull @Valid Uncertainty> uncertainties,
        @JsonProperty(required = true) @NotNull List<@NotNull @Valid Limitation> limitations,
        @JsonProperty(required = true) @NotNull List<@NotNull @Valid Question> questions,
        @JsonProperty(required = true) @NotEmpty @NotNull List<@NotNull @Valid NeedResolutionPlanningInput> resolutions) {

    public SubmitAnswerPlanningInput {
        facts = List.copyOf(Objects.requireNonNull(facts, "answer facts are required"));
        uncertainties = List.copyOf(Objects.requireNonNull(uncertainties, "answer uncertainties are required"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "answer limitations are required"));
        questions = List.copyOf(Objects.requireNonNull(questions, "answer questions are required"));
        resolutions = List.copyOf(Objects.requireNonNull(resolutions, "answer resolutions are required"));
        if (facts.isEmpty() && uncertainties.isEmpty() && limitations.isEmpty() && questions.isEmpty()) {
            throw new IllegalArgumentException("answer requires at least one statement");
        }
    }
}
