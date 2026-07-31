package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxDeferReason;
import com.java.system.agent.interaction.domain.InboxFailure;
import com.java.system.agent.interaction.domain.InboxMessage;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.InboxMessageStatus;
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.domain.FinalInteractionResponse;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.port.in.ClaimRecoveryFailureException;
import com.java.system.agent.interaction.port.out.SessionInboxPort;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;
import com.java.system.agent.answering.port.in.AnswerQuestionUseCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InboxWorkApplicationService 對單次 polling claim 與 processor 委派的 application 邊界測試
 */
class InboxWorkApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");
    private static final AttemptBudget BUDGET = new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0);

    @Test
    void returnsEmptyWithoutCallingTheProcessorWhenNoInboxClaimIsAvailable() {
        RecordingInboxPort inbox = new RecordingInboxPort(Optional.empty());
        AtomicInteger answerCalls = new AtomicInteger();
        InboxWorkApplicationService service = new InboxWorkApplicationService(
                inbox, processor(inbox, command -> {
                    answerCalls.incrementAndGet();
                    return result();
                }), new ClaimAdmissionCoordinator());

        assertThat(service.processNext(NOW)).isEmpty();

        assertThat(inbox.claimCalls).isOne();
        assertThat(answerCalls.get()).isZero();
    }

    @Test
    void processesOneClaimAtTheSuppliedTimeAndReturnsItsOutcome() {
        InboxClaim claim = claim();
        RecordingInboxPort inbox = new RecordingInboxPort(Optional.of(claim));
        AtomicInteger answerCalls = new AtomicInteger();
        InboxWorkApplicationService service = new InboxWorkApplicationService(
                inbox, processor(inbox, command -> {
                    answerCalls.incrementAndGet();
                    return result();
                }), new ClaimAdmissionCoordinator());

        assertThat(service.processNext(NOW)).contains(InboxProcessingOutcome.COMPLETED);

        assertThat(inbox.claimCalls).isOne();
        assertThat(answerCalls.get()).isOne();
        assertThat(inbox.completedClaim).isEqualTo(claim);
        assertThat(inbox.completedAt).isEqualTo(NOW);
    }

    @Test
    void doesNotStartANewDatabaseClaimAfterClaimAdmissionCloses() {
        RecordingInboxPort inbox = new RecordingInboxPort(Optional.empty());
        ClaimAdmissionCoordinator claimAdmission = new ClaimAdmissionCoordinator();
        InboxWorkApplicationService service = new InboxWorkApplicationService(
                inbox, processor(inbox, command -> result()), claimAdmission);

        claimAdmission.stopClaiming();

        assertThat(service.processNext(NOW)).isEmpty();
        assertThat(inbox.claimCalls).isZero();
    }

    @Test
    void recoversTheExactClaimWhenItsTerminalTransitionFailsSoLaterWorkCanProceed() {
        InboxClaim claim = claim();
        RecordingInboxPort inbox = new RecordingInboxPort(Optional.of(claim));
        inbox.failCompletion = true;
        InboxWorkApplicationService service = new InboxWorkApplicationService(
                inbox, processor(inbox, command -> result()), new ClaimAdmissionCoordinator());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.processNext(NOW));

        assertThat(inbox.recoveredClaim).isEqualTo(claim);
        assertThat(inbox.recoveredAt).isEqualTo(NOW);
    }

    @Test
    void throwsClaimRecoveryFailureWhenExactInboxRecoveryReturnsFalse() {
        InboxClaim claim = claim();
        RecordingInboxPort inbox = new RecordingInboxPort(Optional.of(claim));
        inbox.failCompletion = true;
        inbox.recoveryResult = false;
        InboxWorkApplicationService service = new InboxWorkApplicationService(
                inbox, processor(inbox, command -> result()), new ClaimAdmissionCoordinator());

        assertThatThrownBy(() -> service.processNext(NOW))
                .isInstanceOf(ClaimRecoveryFailureException.class)
                .hasMessage("claimed work could not be recovered")
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(exception -> assertThat(exception.getSuppressed()).isEmpty());

        assertThat(inbox.recoveredClaim).isEqualTo(claim);
        assertThat(inbox.recoveredAt).isEqualTo(NOW);
    }

    private static SessionInboxProcessor processor(SessionInboxPort inbox, AnswerQuestionUseCase useCase) {
        return new SessionInboxProcessor(inbox, useCase, BUDGET, InboxRetryPolicy.defaults());
    }

    private static AnswerQuestionResult result() {
        return new AnswerQuestionResult(
                new AnalysisRunId("run-1"), RunOutcome.INCONCLUSIVE, "資訊不足", Optional.empty(),
                RunResponseKind.RUNTIME_NOTICE, Optional.empty(), RevisionVector.empty());
    }

    private static InboxClaim claim() {
        InboxMessage message = new InboxMessage(
                new InboxMessageId("inbox-1"), new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"), new SessionId("session-1"), 0, new AnalysisRunId("run-1"),
                new ParticipantRef("slack", "U123456"), "<@bot> 問題", "問題", InboxMessageStatus.PROCESSING,
                1, NOW, Optional.of(NOW), Optional.<InboxDeferReason>empty(), Optional.empty());
        return new InboxClaim(message);
    }

    private static final class RecordingInboxPort implements SessionInboxPort {

        private final Optional<InboxClaim> nextClaim;
        private int claimCalls;
        private InboxClaim completedClaim;
        private Instant completedAt;
        private InboxClaim recoveredClaim;
        private Instant recoveredAt;
        private boolean failCompletion;
        private boolean recoveryResult = true;

        private RecordingInboxPort(Optional<InboxClaim> nextClaim) {
            this.nextClaim = nextClaim;
        }

        @Override
        public Optional<InboxClaim> claimNext(Instant now) {
            claimCalls++;
            return nextClaim;
        }

        @Override
        public void completeWithFinal(InboxClaim claim, FinalInteractionResponse result, Instant completedAt) {
            if (failCompletion) {
                throw new IllegalStateException("transition unavailable");
            }
            completedClaim = claim;
            this.completedAt = completedAt;
        }

        @Override
        public void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void retry(InboxClaim claim, InboxFailure failure, Instant availableAt) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void deferForCapacity(InboxClaim claim, Instant retryAt) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean recoverClaim(InboxClaim claim, Instant recoveredAt) {
            recoveredClaim = claim;
            this.recoveredAt = recoveredAt;
            return recoveryResult;
        }

        @Override
        public int recoverInterrupted(Instant recoveredAt) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
