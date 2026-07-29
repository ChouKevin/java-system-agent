package com.java.system.agent.interaction.domain;

import com.java.system.agent.interaction.domain.delivery.DeliveryFailure;
import com.java.system.agent.interaction.domain.delivery.DeliveryId;
import com.java.system.agent.interaction.domain.delivery.DeliveryKind;
import com.java.system.agent.interaction.domain.delivery.DeliveryMessage;
import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeliveryMessage 的可持久化狀態不變條件測試
 */
class DeliveryMessageTest {

    private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");

    @Test
    void acceptsTheFixedReceiptWithoutARunResponsePair() {
        assertThatCode(() -> receipt(DeliveryStatus.PENDING, Optional.empty(), Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsReceiptTextOrRunResponsePairThatViolatesTheReceiptContract() {
        assertThatThrownBy(() -> message(
                DeliveryKind.RECEIPT,
                "received",
                Optional.of(RunResponseKind.ANSWER),
                Optional.of(RunOutcome.COMPLETED),
                DeliveryStatus.PENDING,
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(
                DeliveryKind.RECEIPT,
                "received",
                Optional.empty(),
                Optional.empty(),
                DeliveryStatus.PENDING,
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresOnlyValidFinalResponsePairs() {
        assertThatCode(() -> finalResponse(
                RunResponseKind.ANSWER, RunOutcome.COMPLETED, DeliveryStatus.PENDING, Optional.empty(), Optional.empty()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> finalResponse(
                RunResponseKind.CLARIFICATION, RunOutcome.COMPLETED, DeliveryStatus.PENDING, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> message(
                DeliveryKind.FINAL_RESPONSE,
                "response",
                Optional.of(RunResponseKind.ANSWER),
                Optional.empty(),
                DeliveryStatus.PENDING,
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsWaitingOnlyForFinalResponses() {
        assertThatCode(() -> finalResponse(
                RunResponseKind.RUNTIME_NOTICE,
                RunOutcome.FAILED,
                DeliveryStatus.WAITING_FOR_RECEIPT,
                Optional.empty(),
                Optional.empty()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> receipt(DeliveryStatus.WAITING_FOR_RECEIPT, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresProviderIdentityExactlyForDeliveredMessages() {
        assertThatCode(() -> receipt(DeliveryStatus.DELIVERED, Optional.empty(), Optional.of("slack-message-1")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> receipt(DeliveryStatus.DELIVERED, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(DeliveryStatus.PENDING, Optional.empty(), Optional.of("slack-message-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresSafeFailureForBlockedMessages() {
        DeliveryFailure failure = new DeliveryFailure("SLACK_NOT_IN_CHANNEL", "Slack cannot post to the channel");

        assertThatCode(() -> receipt(DeliveryStatus.BLOCKED, Optional.of(failure), Optional.empty()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> receipt(DeliveryStatus.BLOCKED, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnicodeLineSeparatorsFromDeliveryFailureContracts() {
        assertThatThrownBy(() -> new DeliveryFailure("SLACK\u0085ERROR", "safe description"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryFailure("SLACK_ERROR", "safe\u2028description"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryTransportResult.RetryableFailure(NOW, "SLACK\u2029ERROR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryTransportResult.PermanentFailure("SLACK_ERROR", "safe\u0085description"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeliveryMessage receipt(
            DeliveryStatus status,
            Optional<DeliveryFailure> failure,
            Optional<String> providerMessageId) {
        return message(
                DeliveryKind.RECEIPT,
                "已接收",
                Optional.empty(),
                Optional.empty(),
                status,
                failure,
                providerMessageId);
    }

    private static DeliveryMessage finalResponse(
            RunResponseKind responseKind,
            RunOutcome outcome,
            DeliveryStatus status,
            Optional<DeliveryFailure> failure,
            Optional<String> providerMessageId) {
        return message(
                DeliveryKind.FINAL_RESPONSE,
                "response",
                Optional.of(responseKind),
                Optional.of(outcome),
                status,
                failure,
                providerMessageId);
    }

    private static DeliveryMessage message(
            DeliveryKind kind,
            String responseText,
            Optional<RunResponseKind> responseKind,
            Optional<RunOutcome> outcome,
            DeliveryStatus status,
            Optional<DeliveryFailure> failure,
            Optional<String> providerMessageId) {
        return new DeliveryMessage(
                new DeliveryId("delivery-1"),
                new InboxMessageId("inbox-1"),
                new AnalysisRunId("run-1"),
                kind,
                responseKind,
                outcome,
                new SessionSourceRef("slack", "workspace:channel:thread"),
                new ParticipantRef("slack", "U123"),
                responseText,
                status,
                0,
                NOW,
                failure,
                providerMessageId,
                NOW,
                NOW);
    }
}
