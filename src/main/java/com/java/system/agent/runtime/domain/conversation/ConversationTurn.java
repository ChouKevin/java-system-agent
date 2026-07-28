package com.java.system.agent.runtime.domain.conversation;

import com.java.system.agent.runtime.domain.run.AnalysisRunId;

import java.util.Objects;

/**
 * 已驗證且可 append 到對話歷史的不可變回應
 */
public record ConversationTurn(
        AnalysisRunId runId,
        ParticipantRef participant,
        String userMessage,
        String assistantMessage,
        ConversationTurnType type) {

    public ConversationTurn {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(participant, "conversation participant must not be null");
        Objects.requireNonNull(userMessage, "conversation user message must not be null");
        Objects.requireNonNull(assistantMessage, "conversation assistant message must not be null");
        Objects.requireNonNull(type, "conversation turn type must not be null");
        if (userMessage.isBlank() || assistantMessage.isBlank()) {
            throw new IllegalArgumentException("conversation messages must not be blank");
        }
    }
}
