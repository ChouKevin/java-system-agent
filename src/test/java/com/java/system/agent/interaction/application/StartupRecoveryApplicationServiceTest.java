package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxFailure;
import com.java.system.agent.interaction.domain.RecoverySummary;
import com.java.system.agent.interaction.domain.delivery.DeliveryClaim;
import com.java.system.agent.interaction.domain.delivery.DeliveryFailure;
import com.java.system.agent.interaction.port.out.DeliveryOutboxPort;
import com.java.system.agent.interaction.port.out.SessionInboxPort;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StartupRecoveryApplicationService 對兩種 interrupted work 的復原聚合測試
 */
class StartupRecoveryApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");

    @Test
    void recoversInboxBeforeDeliveryAndReturnsBothCounts() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingDeliveryOutbox outbox = new RecordingDeliveryOutbox(inbox);
        StartupRecoveryApplicationService service = new StartupRecoveryApplicationService(inbox, outbox);

        assertThat(service.recoverInterrupted(NOW)).isEqualTo(new RecoverySummary(2, 3));
        assertThat(inbox.recoveredAt).isEqualTo(NOW);
        assertThat(outbox.recoveredAt).isEqualTo(NOW);
    }

    @Test
    void rejectsNegativeRecoveryCounts() {
        assertThatThrownBy(() -> new RecoverySummary(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingInboxPort implements SessionInboxPort {

        private Instant recoveredAt;

        @Override public Optional<InboxClaim> claimNext(Instant now) { throw new UnsupportedOperationException(); }
        @Override public void completeWithFinal(InboxClaim claim, AnswerQuestionResult result, Instant completedAt) { throw new UnsupportedOperationException(); }
        @Override public void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt) { throw new UnsupportedOperationException(); }
        @Override public void retry(InboxClaim claim, InboxFailure failure, Instant availableAt) { throw new UnsupportedOperationException(); }
        @Override public void deferForCapacity(InboxClaim claim, Instant retryAt) { throw new UnsupportedOperationException(); }
        @Override public int recoverInterrupted(Instant recoveredAt) { this.recoveredAt = recoveredAt; return 2; }
    }

    private static final class RecordingDeliveryOutbox implements DeliveryOutboxPort {

        private final RecordingInboxPort inbox;
        private Instant recoveredAt;

        private RecordingDeliveryOutbox(RecordingInboxPort inbox) { this.inbox = inbox; }

        @Override public Optional<DeliveryClaim> claimNext(Instant now) { throw new UnsupportedOperationException(); }
        @Override public void recordDelivered(DeliveryClaim claim, String providerMessageId, Instant deliveredAt) { throw new UnsupportedOperationException(); }
        @Override public void recordRetry(DeliveryClaim claim, DeliveryFailure failure, Instant retryAt, Instant updatedAt) { throw new UnsupportedOperationException(); }
        @Override public void recordBlocked(DeliveryClaim claim, DeliveryFailure failure, Instant blockedAt) { throw new UnsupportedOperationException(); }
        @Override public int recoverInterrupted(Instant recoveredAt) {
            assertThat(inbox.recoveredAt).isEqualTo(recoveredAt);
            this.recoveredAt = recoveredAt;
            return 3;
        }
    }
}
