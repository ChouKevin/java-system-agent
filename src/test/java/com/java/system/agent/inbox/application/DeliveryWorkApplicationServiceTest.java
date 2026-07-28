package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.delivery.DeliveryClaim;
import com.java.system.agent.inbox.domain.delivery.DeliveryFailure;
import com.java.system.agent.inbox.domain.delivery.DeliveryId;
import com.java.system.agent.inbox.domain.delivery.DeliveryKind;
import com.java.system.agent.inbox.domain.delivery.DeliveryMessage;
import com.java.system.agent.inbox.domain.delivery.DeliveryProcessingOutcome;
import com.java.system.agent.inbox.domain.delivery.DeliveryStatus;
import com.java.system.agent.inbox.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.inbox.port.in.ClaimRecoveryFailureException;
import com.java.system.agent.inbox.port.out.DeliveryOutboxPort;
import com.java.system.agent.inbox.port.out.DeliveryTransportPort;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeliveryWorkApplicationService 對單次 polling claim 與 processor 委派的 application 邊界測試
 */
class DeliveryWorkApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");

    @Test
    void returnsEmptyWithoutCallingTheProcessorWhenNoDeliveryClaimIsAvailable() {
        RecordingOutbox outbox = new RecordingOutbox(Optional.empty());
        AtomicInteger deliveryCalls = new AtomicInteger();
        DeliveryWorkApplicationService service = new DeliveryWorkApplicationService(
                outbox, processor(outbox, (message, now) -> {
                    deliveryCalls.incrementAndGet();
                    return new DeliveryTransportResult.Delivered("provider-1");
                }), new ClaimAdmissionCoordinator());

        assertThat(service.processNext(NOW)).isEmpty();

        assertThat(outbox.claimCalls).isOne();
        assertThat(deliveryCalls.get()).isZero();
    }

    @Test
    void processesOneClaimAtTheSuppliedTimeAndReturnsItsOutcome() {
        DeliveryClaim claim = claim();
        RecordingOutbox outbox = new RecordingOutbox(Optional.of(claim));
        AtomicInteger deliveryCalls = new AtomicInteger();
        DeliveryWorkApplicationService service = new DeliveryWorkApplicationService(
                outbox, processor(outbox, (message, now) -> {
                    deliveryCalls.incrementAndGet();
                    return new DeliveryTransportResult.Delivered("provider-1");
                }), new ClaimAdmissionCoordinator());

        assertThat(service.processNext(NOW)).contains(DeliveryProcessingOutcome.DELIVERED);

        assertThat(outbox.claimCalls).isOne();
        assertThat(deliveryCalls.get()).isOne();
        assertThat(outbox.deliveredClaim).isEqualTo(claim);
        assertThat(outbox.deliveredAt).isEqualTo(NOW);
    }

    @Test
    void doesNotStartANewDatabaseClaimAfterClaimAdmissionCloses() {
        RecordingOutbox outbox = new RecordingOutbox(Optional.empty());
        ClaimAdmissionCoordinator claimAdmission = new ClaimAdmissionCoordinator();
        DeliveryWorkApplicationService service = new DeliveryWorkApplicationService(
                outbox, processor(outbox, (message, now) -> new DeliveryTransportResult.Delivered("provider-1")),
                claimAdmission);

        claimAdmission.stopClaiming();

        assertThat(service.processNext(NOW)).isEmpty();
        assertThat(outbox.claimCalls).isZero();
    }

    @Test
    void recoversTheExactClaimWhenItsDeliveryTransitionFailsSoLaterWorkCanProceed() {
        DeliveryClaim claim = claim();
        RecordingOutbox outbox = new RecordingOutbox(Optional.of(claim));
        outbox.failDeliveryTransition = true;
        DeliveryWorkApplicationService service = new DeliveryWorkApplicationService(
                outbox, processor(outbox, (message, now) -> new DeliveryTransportResult.Delivered("provider-1")),
                new ClaimAdmissionCoordinator());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.processNext(NOW));

        assertThat(outbox.recoveredClaim).isEqualTo(claim);
        assertThat(outbox.recoveredAt).isEqualTo(NOW);
    }

    @Test
    void throwsClaimRecoveryFailureWhenExactDeliveryRecoveryThrows() {
        DeliveryClaim claim = claim();
        RecordingOutbox outbox = new RecordingOutbox(Optional.of(claim));
        outbox.failDeliveryTransition = true;
        outbox.failRecovery = true;
        DeliveryWorkApplicationService service = new DeliveryWorkApplicationService(
                outbox, processor(outbox, (message, now) -> new DeliveryTransportResult.Delivered("provider-1")),
                new ClaimAdmissionCoordinator());

        assertThatThrownBy(() -> service.processNext(NOW))
                .isInstanceOf(ClaimRecoveryFailureException.class)
                .hasMessage("claimed work could not be recovered")
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(exception -> {
                    assertThat(exception.getSuppressed()).hasSize(1);
                    assertThat(exception.getSuppressed()[0]).isInstanceOf(IllegalStateException.class)
                            .hasMessage("recovery unavailable");
                });

        assertThat(outbox.recoveredClaim).isEqualTo(claim);
        assertThat(outbox.recoveredAt).isEqualTo(NOW);
    }

    private static DeliveryProcessor processor(DeliveryOutboxPort outbox, DeliveryTransportPort transport) {
        DeliveryRetryPolicy retryPolicy = new DeliveryRetryPolicy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), attempt -> Duration.ZERO);
        return new DeliveryProcessor(outbox, transport, retryPolicy);
    }

    private static DeliveryClaim claim() {
        DeliveryMessage message = new DeliveryMessage(
                new DeliveryId("delivery-1"), new InboxMessageId("inbox-1"), new AnalysisRunId("run-1"),
                DeliveryKind.RECEIPT, Optional.empty(), Optional.empty(), new SessionSourceRef("slack", "channel:thread"),
                new ParticipantRef("slack", "U123456"), "已接收", DeliveryStatus.PROCESSING, 1,
                NOW, Optional.empty(), Optional.empty(), NOW, NOW);
        return new DeliveryClaim(message);
    }

    private static final class RecordingOutbox implements DeliveryOutboxPort {

        private final Optional<DeliveryClaim> nextClaim;
        private int claimCalls;
        private DeliveryClaim deliveredClaim;
        private Instant deliveredAt;
        private DeliveryClaim recoveredClaim;
        private Instant recoveredAt;
        private boolean failDeliveryTransition;
        private boolean failRecovery;

        private RecordingOutbox(Optional<DeliveryClaim> nextClaim) {
            this.nextClaim = nextClaim;
        }

        @Override
        public Optional<DeliveryClaim> claimNext(Instant now) {
            claimCalls++;
            return nextClaim;
        }

        @Override
        public void recordDelivered(DeliveryClaim claim, String providerMessageId, Instant deliveredAt) {
            if (failDeliveryTransition) {
                throw new IllegalStateException("transition unavailable");
            }
            deliveredClaim = claim;
            this.deliveredAt = deliveredAt;
        }

        @Override
        public void recordRetry(DeliveryClaim claim, DeliveryFailure failure, Instant retryAt, Instant updatedAt) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void recordBlocked(DeliveryClaim claim, DeliveryFailure failure, Instant blockedAt) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean recoverClaim(DeliveryClaim claim, Instant recoveredAt) {
            recoveredClaim = claim;
            this.recoveredAt = recoveredAt;
            if (failRecovery) {
                throw new IllegalStateException("recovery unavailable");
            }
            return true;
        }

        @Override
        public int recoverInterrupted(Instant recoveredAt) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
