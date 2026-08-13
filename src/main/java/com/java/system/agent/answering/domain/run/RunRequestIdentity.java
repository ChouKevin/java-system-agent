package com.java.system.agent.answering.domain.run;

import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.scope.RepositoryId;

import java.util.Objects;

/**
 * Agent Run 持久化擁有的不可變 session 與問題識別
 */
public record RunRequestIdentity(
        String sessionIdValue,
        ParticipantRef participant,
        String questionText,
        RepositoryId repositoryId) {

    public RunRequestIdentity {
        Objects.requireNonNull(sessionIdValue, "run session ID value must not be null");
        Objects.requireNonNull(participant, "run participant must not be null");
        Objects.requireNonNull(questionText, "run question text must not be null");
        Objects.requireNonNull(repositoryId, "run repository ID must not be null");
        if (sessionIdValue.isBlank() || questionText.isBlank()) {
            throw new IllegalArgumentException("run request identity values must not be blank");
        }
    }
}
