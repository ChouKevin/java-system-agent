package com.java.system.agent.inbox.domain.delivery;

import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunResponseKind;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 具完整 retry 與結果語意的 durable delivery outbox 訊息
 */
public record DeliveryMessage(
        DeliveryId deliveryId,
        InboxMessageId inboxMessageId,
        AnalysisRunId runId,
        DeliveryKind kind,
        Optional<RunResponseKind> responseKind,
        Optional<RunOutcome> outcome,
        SessionSourceRef sessionSource,
        ParticipantRef participant,
        String responseText,
        DeliveryStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        Optional<DeliveryFailure> lastFailure,
        Optional<String> providerMessageId,
        Instant createdAt,
        Instant updatedAt) {

    public DeliveryMessage {
        Objects.requireNonNull(deliveryId, "delivery ID must not be null");
        Objects.requireNonNull(inboxMessageId, "inbox message ID must not be null");
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(kind, "delivery kind must not be null");
        Objects.requireNonNull(responseKind, "response kind must not be null");
        Objects.requireNonNull(outcome, "run outcome must not be null");
        Objects.requireNonNull(sessionSource, "session source must not be null");
        Objects.requireNonNull(participant, "participant must not be null");
        Objects.requireNonNull(responseText, "response text must not be null");
        Objects.requireNonNull(status, "delivery status must not be null");
        Objects.requireNonNull(nextAttemptAt, "next attempt at must not be null");
        Objects.requireNonNull(lastFailure, "last failure must not be null");
        Objects.requireNonNull(providerMessageId, "provider message ID must not be null");
        Objects.requireNonNull(createdAt, "created at must not be null");
        Objects.requireNonNull(updatedAt, "updated at must not be null");
        if (responseText.isBlank()) {
            throw new IllegalArgumentException("response text must not be blank");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attempt count must not be negative");
        }
        validateResponseContract(kind, responseText, responseKind, outcome);
        if (status == DeliveryStatus.WAITING_FOR_RECEIPT && kind != DeliveryKind.FINAL_RESPONSE) {
            throw new IllegalArgumentException("only a final response may wait for its receipt");
        }
        validateProviderMessageId(status, providerMessageId);
        if (status == DeliveryStatus.BLOCKED && !lastFailure.isPresent()) {
            throw new IllegalArgumentException("blocked delivery must contain a safe failure");
        }
    }

    private static void validateResponseContract(
            DeliveryKind kind,
            String responseText,
            Optional<RunResponseKind> responseKind,
            Optional<RunOutcome> outcome) {
        if (kind == DeliveryKind.RECEIPT) {
            if (!"已接收".equals(responseText) || responseKind.isPresent() || outcome.isPresent()) {
                throw new IllegalArgumentException("receipt delivery must use its fixed text without a run response pair");
            }
            return;
        }
        if (!responseKind.isPresent() || !outcome.isPresent()
                || !isValidFinalResponsePair(responseKind.orElseThrow(), outcome.orElseThrow())) {
            throw new IllegalArgumentException("final delivery must retain a valid run response pair");
        }
    }

    private static boolean isValidFinalResponsePair(RunResponseKind responseKind, RunOutcome outcome) {
        return switch (responseKind) {
            case ANSWER -> outcome == RunOutcome.COMPLETED || outcome == RunOutcome.INCONCLUSIVE;
            case CLARIFICATION -> outcome == RunOutcome.INCONCLUSIVE;
            case RUNTIME_NOTICE -> outcome == RunOutcome.INCONCLUSIVE
                    || outcome == RunOutcome.FAILED
                    || outcome == RunOutcome.CANCELLED;
        };
    }

    private static void validateProviderMessageId(DeliveryStatus status, Optional<String> providerMessageId) {
        if (status == DeliveryStatus.DELIVERED && !providerMessageId.isPresent()) {
            throw new IllegalArgumentException("delivered message must contain a provider message ID");
        }
        if (status != DeliveryStatus.DELIVERED && providerMessageId.isPresent()) {
            throw new IllegalArgumentException("only delivered message may contain a provider message ID");
        }
        providerMessageId.ifPresent(value -> DeliveryId.requiredOpaqueValue(value, "provider message ID"));
    }
}
