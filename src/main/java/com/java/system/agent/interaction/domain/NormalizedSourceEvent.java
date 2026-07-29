package com.java.system.agent.interaction.domain;

import com.java.system.agent.answering.domain.conversation.ParticipantRef;

import java.time.Instant;
import java.util.Objects;

/**
 * 已由傳輸 adapter 正規化且尚未 durable admission 的來源事件
 */
public record NormalizedSourceEvent(
        String sourceType,
        TransportEventId transportEventId,
        SourceMessageId sourceMessageId,
        SessionSourceRef sessionSource,
        ParticipantRef participant,
        String sourceText,
        String questionText,
        SourcePayloadFingerprintV1 fingerprint,
        Instant receivedAt) {

    public NormalizedSourceEvent {
        sourceType = InboxMessageId.requiredOpaqueValue(sourceType, "source type");
        Objects.requireNonNull(transportEventId, "transport event ID must not be null");
        Objects.requireNonNull(sourceMessageId, "source message ID must not be null");
        Objects.requireNonNull(sessionSource, "session source must not be null");
        Objects.requireNonNull(participant, "participant must not be null");
        Objects.requireNonNull(sourceText, "source text must not be null");
        Objects.requireNonNull(questionText, "question text must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(receivedAt, "received at must not be null");
        if (questionText.isBlank()) {
            throw new IllegalArgumentException("question text must not be blank");
        }
    }
}
