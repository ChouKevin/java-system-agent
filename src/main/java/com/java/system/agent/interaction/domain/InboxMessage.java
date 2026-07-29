package com.java.system.agent.interaction.domain;

import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AnalysisRunId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable session inbox 中一筆依來源順序處理的訊息
 */
public record InboxMessage(
        InboxMessageId inboxMessageId,
        SessionSourceRef source,
        SourceMessageId sourceMessageId,
        SessionId sessionId,
        long sessionSequence,
        AnalysisRunId runId,
        ParticipantRef participant,
        String sourceText,
        String questionText,
        InboxMessageStatus status,
        int attemptCount,
        Instant availableAt,
        Optional<Instant> claimedAt,
        Optional<InboxDeferReason> deferReason,
        Optional<InboxFailure> lastFailure) {

    public InboxMessage {
        Objects.requireNonNull(inboxMessageId, "inbox message ID must not be null");
        Objects.requireNonNull(source, "session source reference must not be null");
        Objects.requireNonNull(sourceMessageId, "source message ID must not be null");
        Objects.requireNonNull(sessionId, "session ID must not be null");
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(participant, "participant must not be null");
        Objects.requireNonNull(sourceText, "source text must not be null");
        Objects.requireNonNull(questionText, "question text must not be null");
        Objects.requireNonNull(status, "inbox message status must not be null");
        Objects.requireNonNull(availableAt, "available at must not be null");
        Objects.requireNonNull(claimedAt, "claimed at must not be null");
        Objects.requireNonNull(deferReason, "defer reason must not be null");
        Objects.requireNonNull(lastFailure, "last failure must not be null");
        if (sessionSequence < 0) {
            throw new IllegalArgumentException("session sequence must not be negative");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attempt count must not be negative");
        }
        if (status == InboxMessageStatus.PROCESSING && attemptCount < 1) {
            throw new IllegalArgumentException("processing inbox message must have at least one attempt");
        }
        if (questionText.isBlank()) {
            throw new IllegalArgumentException("question text must not be blank");
        }
        if (status == InboxMessageStatus.PROCESSING && claimedAt.isEmpty()) {
            throw new IllegalArgumentException("processing inbox message must have a claim timestamp");
        }
    }
}
