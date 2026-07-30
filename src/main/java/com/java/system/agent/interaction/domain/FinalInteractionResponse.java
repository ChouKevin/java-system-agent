package com.java.system.agent.interaction.domain;

import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;

import java.util.Objects;

/**
 * Interaction 完成 inbox 與建立最終 delivery 所需的最小 terminal 回應投影
 */
public record FinalInteractionResponse(
        AnalysisRunId runId,
        RunOutcome outcome,
        RunResponseKind responseKind,
        String responseText) {

    public FinalInteractionResponse {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(outcome, "run outcome must not be null");
        Objects.requireNonNull(responseKind, "run response kind must not be null");
        Objects.requireNonNull(responseText, "response text must not be null");
        if (responseText.isBlank()) {
            throw new IllegalArgumentException("response text must not be blank");
        }
    }
}
