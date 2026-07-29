package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.delivery.DeliveryClaim;
import com.java.system.agent.interaction.domain.delivery.DeliveryFailure;
import com.java.system.agent.interaction.domain.delivery.DeliveryId;
import com.java.system.agent.interaction.domain.delivery.DeliveryKind;
import com.java.system.agent.interaction.domain.delivery.DeliveryMessage;
import com.java.system.agent.interaction.domain.delivery.DeliveryProcessingOutcome;
import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.interaction.port.out.DeliveryOutboxPort;
import com.java.system.agent.interaction.port.out.DeliveryTransportPort;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeliveryProcessor 對 transport 結果與安全重試 transition 的 application 邊界測試
 */
class DeliveryProcessorTest {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");

    @Test
    void recordsProviderDelivery() {
        RecordingOutbox outbox = new RecordingOutbox();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox, (message, now) -> new DeliveryTransportResult.Delivered("provider-1"), retryPolicy());

        assertThat(processor.process(claim(1), NOW)).isEqualTo(DeliveryProcessingOutcome.DELIVERED);
        assertThat(outbox.providerMessageId).isEqualTo("provider-1");
        assertThat(outbox.deliveredAt).isEqualTo(NOW);
    }

    @Test
    void usesTheTransportSuppliedRetryTime() {
        RecordingOutbox outbox = new RecordingOutbox();
        Instant retryAt = NOW.plusSeconds(42);
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> new DeliveryTransportResult.RetryableFailure(retryAt, "SLACK_RATE_LIMIT"),
                retryPolicy());

        assertThat(processor.process(claim(1), NOW)).isEqualTo(DeliveryProcessingOutcome.RETRY_SCHEDULED);
        assertThat(outbox.retryAt).isEqualTo(retryAt);
        assertThat(outbox.failure).isEqualTo(new DeliveryFailure("SLACK_RATE_LIMIT", "Delivery transport requested retry"));
    }

    @Test
    void appliesPolicyBackoffWhenTransportRetryTimeIsNotFuture() {
        assertPolicyBackoffFor(NOW);
        assertPolicyBackoffFor(NOW.minusSeconds(1));
    }

    @Test
    void schedulesRetryableDeliveryFailureOnTheFourthAttempt() {
        RecordingOutbox outbox = new RecordingOutbox();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> new DeliveryTransportResult.RetryableFailure(NOW.plusSeconds(42), "SLACK_RATE_LIMIT"),
                retryPolicy());

        assertThat(processor.process(claim(4), NOW)).isEqualTo(DeliveryProcessingOutcome.RETRY_SCHEDULED);

        assertThat(outbox.retryCalls).isOne();
        assertThat(outbox.blockedCalls).isZero();
    }

    @Test
    void blocksRetryableDeliveryFailureOnTheFifthAttemptWithoutSchedulingAnotherRetry() {
        RecordingOutbox outbox = new RecordingOutbox();
        RecordingMetrics metrics = new RecordingMetrics();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> new DeliveryTransportResult.RetryableFailure(NOW.plusSeconds(42), "SLACK_RATE_LIMIT"),
                retryPolicy(),
                metrics);

        assertThat(processor.process(claim(5), NOW)).isEqualTo(DeliveryProcessingOutcome.BLOCKED);

        assertThat(outbox.retryCalls).isZero();
        assertThat(outbox.blockedCalls).isOne();
        assertThat(outbox.failure).isEqualTo(new DeliveryFailure("SLACK_RATE_LIMIT", "Delivery transport requested retry"));
        assertThat(metrics.retried).isZero();
        assertThat(metrics.blocked).isOne();
    }

    @Test
    void blocksUnexpectedDeliveryExceptionOnTheFifthAttemptWithoutSchedulingAnotherRetry() {
        RecordingOutbox outbox = new RecordingOutbox();
        RecordingMetrics metrics = new RecordingMetrics();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> {
                    throw new IllegalStateException("provider body must not persist");
                },
                retryPolicy(),
                metrics);

        assertThat(processor.process(claim(5), NOW)).isEqualTo(DeliveryProcessingOutcome.BLOCKED);

        assertThat(outbox.retryCalls).isZero();
        assertThat(outbox.blockedCalls).isOne();
        assertThat(outbox.failure).isEqualTo(new DeliveryFailure("DELIVERY_UNEXPECTED", "Unexpected delivery failure"));
        assertThat(metrics.retried).isZero();
        assertThat(metrics.blocked).isOne();
    }

    @Test
    void blocksRecoveredDeliveryBeyondTheMaximumBeforeCallingTheTransport() {
        RecordingOutbox outbox = new RecordingOutbox();
        RecordingMetrics metrics = new RecordingMetrics();
        AtomicInteger transportCalls = new AtomicInteger();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> {
                    transportCalls.incrementAndGet();
                    return new DeliveryTransportResult.Delivered("provider-1");
                },
                retryPolicy(),
                metrics);

        assertThat(processor.process(claim(6), NOW)).isEqualTo(DeliveryProcessingOutcome.BLOCKED);

        assertThat(transportCalls).hasValue(0);
        assertThat(outbox.retryCalls).isZero();
        assertThat(outbox.blockedCalls).isOne();
        assertThat(outbox.failure).isEqualTo(new DeliveryFailure(
                "DELIVERY_ATTEMPTS_EXHAUSTED", "Delivery attempts exhausted; manual intervention required"));
        assertThat(metrics.retried).isZero();
        assertThat(metrics.blocked).isOne();
    }

    @Test
    void blocksPermanentDeliveryFailure() {
        RecordingOutbox outbox = new RecordingOutbox();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> new DeliveryTransportResult.PermanentFailure("SLACK_DENIED", "channel unavailable"),
                retryPolicy());

        assertThat(processor.process(claim(1), NOW)).isEqualTo(DeliveryProcessingOutcome.BLOCKED);
        assertThat(outbox.failure).isEqualTo(new DeliveryFailure("SLACK_DENIED", "channel unavailable"));
        assertThat(outbox.blockedAt).isEqualTo(NOW);
    }

    @Test
    void retriesUnexpectedFailureWithCappedExponentialDelayAndSafeFailure() {
        RecordingOutbox outbox = new RecordingOutbox();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> {
                    throw new IllegalStateException("provider body must not persist");
                },
                retryPolicy());

        assertThat(processor.process(claim(4), NOW)).isEqualTo(DeliveryProcessingOutcome.RETRY_SCHEDULED);
        assertThat(outbox.retryAt).isEqualTo(NOW.plusSeconds(11));
        assertThat(outbox.failure).isEqualTo(new DeliveryFailure("DELIVERY_UNEXPECTED", "Unexpected delivery failure"));
    }

    @Test
    void clampsRetryTimeToInstantMaxWhenTheDelayOverflowsTheInstantRange() {
        DeliveryRetryPolicy policy = retryPolicy();

        assertThat(policy.retryAt(1, Instant.MAX.minusSeconds(1))).isEqualTo(Instant.MAX);
    }

    @Test
    void rejectsNegativeJitterReturnedByTheInjectedCalculation() {
        DeliveryRetryPolicy policy = new DeliveryRetryPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(10), attempt -> Duration.ofSeconds(-1));

        assertThatThrownBy(() -> policy.retryAt(1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delivery retry jitter must not be negative");
    }

    @Test
    void defaultsTheMaximumDeliveryAttemptsToFive() {
        DeliveryRetryPolicy policy = retryPolicy();

        assertThat(policy.maximumAttempts()).isEqualTo(5);
    }

    @Test
    void rejectsANonpositiveMaximumDeliveryAttemptCount() {
        assertThatThrownBy(() -> new DeliveryRetryPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(10), attempt -> Duration.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delivery maximum attempts must be positive");
    }

    private static DeliveryRetryPolicy retryPolicy() {
        return new DeliveryRetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(10), attempt -> Duration.ofSeconds(1));
    }

    private static void assertPolicyBackoffFor(Instant suppliedRetryAt) {
        RecordingOutbox outbox = new RecordingOutbox();
        DeliveryRetryPolicy policy = retryPolicy();
        DeliveryProcessor processor = new DeliveryProcessor(
                outbox,
                (message, now) -> new DeliveryTransportResult.RetryableFailure(suppliedRetryAt, "SLACK_AMBIGUOUS_IO"),
                policy);

        assertThat(processor.process(claim(1), NOW)).isEqualTo(DeliveryProcessingOutcome.RETRY_SCHEDULED);
        assertThat(outbox.retryAt).isEqualTo(policy.retryAt(1, NOW));
        assertThat(outbox.failure).isEqualTo(new DeliveryFailure("SLACK_AMBIGUOUS_IO", "Delivery transport requested retry"));
    }

    private static DeliveryClaim claim(int attemptCount) {
        DeliveryMessage message = new DeliveryMessage(
                new DeliveryId("delivery-1"), new InboxMessageId("inbox-1"), new AnalysisRunId("run-1"),
                DeliveryKind.RECEIPT, Optional.empty(), Optional.empty(), new SessionSourceRef("slack", "channel:thread"),
                new ParticipantRef("slack", "U123456"), "已接收", DeliveryStatus.PROCESSING, attemptCount,
                NOW, Optional.empty(), Optional.empty(), NOW, NOW);
        return new DeliveryClaim(message);
    }

    private static final class RecordingOutbox implements DeliveryOutboxPort {

        private String providerMessageId;
        private Instant deliveredAt;
        private Instant retryAt;
        private DeliveryFailure failure;
        private Instant blockedAt;
        private int retryCalls;
        private int blockedCalls;

        @Override
        public Optional<DeliveryClaim> claimNext(Instant now) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void recordDelivered(DeliveryClaim claim, String providerMessageId, Instant deliveredAt) {
            this.providerMessageId = providerMessageId;
            this.deliveredAt = deliveredAt;
        }

        @Override
        public void recordRetry(DeliveryClaim claim, DeliveryFailure failure, Instant retryAt, Instant updatedAt) {
            retryCalls++;
            this.failure = failure;
            this.retryAt = retryAt;
        }

        @Override
        public void recordBlocked(DeliveryClaim claim, DeliveryFailure failure, Instant blockedAt) {
            blockedCalls++;
            this.failure = failure;
            this.blockedAt = blockedAt;
        }

        @Override
        public int recoverInterrupted(Instant recoveredAt) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class RecordingMetrics implements InboxLifecycleMetrics {

        private int retried;
        private int blocked;

        @Override
        public void deliveryRetried() {
            retried++;
        }

        @Override
        public void deliveryBlocked() {
            blocked++;
        }
    }
}
