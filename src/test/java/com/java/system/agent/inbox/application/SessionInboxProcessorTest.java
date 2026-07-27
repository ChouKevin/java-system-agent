package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.InboxEnqueueRequest;
import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.InboxMessageStatus;
import com.java.system.agent.inbox.domain.InboxProcessingOutcome;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.port.in.EnqueueSessionMessageCommand;
import com.java.system.agent.inbox.port.out.SessionInboxPort;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunResponseKind;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.in.AnswerQuestionCommand;
import com.java.system.agent.runtime.port.in.AnswerExecutionMode;
import com.java.system.agent.runtime.port.in.AnswerExecutionContractException;
import com.java.system.agent.runtime.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SessionInboxProcessor 對已認領訊息的 durable transition 邊界測試
 */
class SessionInboxProcessorTest {

    private static final AttemptBudget BUDGET = new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0);
    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");

    @ParameterizedTest
    @EnumSource(RunOutcome.class)
    void completesTheClaimedInboxForEveryValidRunOutcome(RunOutcome runOutcome) {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingAnswerQuestionUseCase answerQuestion = new RecordingAnswerQuestionUseCase(result(runOutcome));
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, answerQuestion, BUDGET, InboxRetryPolicy.defaults());
        InboxMessage claimed = claimed(1);

        InboxProcessingOutcome outcome = processor.process(claimed, NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(inbox.completed).isEqualTo(claimed);
        assertThat(inbox.completedAt).isEqualTo(NOW);
        assertThat(answerQuestion.command).isEqualTo(new AnswerQuestionCommand(
                claimed.runId(), claimed.sessionId(), claimed.exactQuestion(), BUDGET,
                AnswerExecutionMode.INITIAL, 1));
        assertThat(answerQuestion.command.question()).isEqualTo(claimed.exactQuestion());
        assertThat(answerQuestion.invocationCount).isOne();
    }

    @Test
    void retriesUnexpectedFailuresWithExponentialDelayThenFailsWhilePreservingTheClaimedMessage() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new IllegalStateException("provider payload must not persist");
                }, BUDGET, InboxRetryPolicy.defaults());
        InboxMessage firstAttempt = claimed(1);
        InboxMessage secondAttempt = claimed(2);
        InboxMessage thirdAttempt = claimed(3);

        assertThat(processor.process(firstAttempt, NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.retriedAvailableAt).isEqualTo(NOW.plusSeconds(1));
        assertThat(inbox.retried).isEqualTo(firstAttempt);
        assertThat(inbox.failure.code()).isEqualTo("ANSWER_UNEXPECTED");
        assertThat(inbox.failure.description()).doesNotContain("provider payload");

        assertThat(processor.process(secondAttempt, NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.retriedAvailableAt).isEqualTo(NOW.plusSeconds(2));
        assertThat(inbox.retried).isEqualTo(secondAttempt);

        assertThat(processor.process(thirdAttempt, NOW)).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failed).isEqualTo(thirdAttempt);
        assertThat(inbox.failedAt).isEqualTo(NOW);
    }

    @Test
    void retriesAnUnavailableAnswerVerifierWithOnlyTheFixedSafeFailure() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new AnswerExecutionUnavailableException(
                            "provider payload must not persist", new IllegalStateException("connection refused"));
                }, BUDGET, InboxRetryPolicy.defaults());

        InboxProcessingOutcome outcome = processor.process(claimed(1), NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.failure.code()).isEqualTo("ANSWER_VERIFIER_UNAVAILABLE");
        assertThat(inbox.failure.description())
                .doesNotContain("provider payload", "connection refused", claimed(1).exactQuestion());
    }

    @Test
    void failsAnAnswerIntegrationContractViolationWithoutRetry() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new AnswerExecutionContractException("provider response violated the contract");
                }, BUDGET, InboxRetryPolicy.defaults());
        InboxMessage claimed = claimed(1);

        InboxProcessingOutcome outcome = processor.process(claimed, NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failed).isEqualTo(claimed);
        assertThat(inbox.retried).isNull();
        assertThat(inbox.failure.code()).isEqualTo("ANSWER_INTEGRATION_CONTRACT");
        assertThat(inbox.failure.description()).doesNotContain("provider response");
    }

    @Test
    void completesAnExhaustedMessageWhenTerminalReconciliationReturnsFailed() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingAnswerQuestionUseCase answerQuestion = new RecordingAnswerQuestionUseCase(result(RunOutcome.FAILED));
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, answerQuestion, BUDGET, InboxRetryPolicy.defaults());
        InboxMessage terminalClaim = claimed(4);

        InboxProcessingOutcome outcome = processor.process(terminalClaim, NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(inbox.completed).isEqualTo(terminalClaim);
        assertThat(answerQuestion.command.executionMode()).isEqualTo(AnswerExecutionMode.TERMINAL_RECONCILIATION);
    }

    @Test
    void retriesANullAnswerResultAsAnInfrastructureFailure() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> null, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claimed(1), NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);

        assertThat(inbox.retried).isEqualTo(claimed(1));
        assertThat(inbox.retriedAvailableAt).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void derivesExecutionModeAndAttemptFromTheDurablyClaimedAttempt() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingAnswerQuestionUseCase answerQuestion = new RecordingAnswerQuestionUseCase(result(RunOutcome.FAILED));
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, answerQuestion, BUDGET, InboxRetryPolicy.defaults());

        processor.process(claimed(1), NOW);
        assertThat(answerQuestion.command.executionMode()).isEqualTo(AnswerExecutionMode.INITIAL);
        assertThat(answerQuestion.command.executionAttempt()).isEqualTo(1);

        processor.process(claimed(2), NOW);
        assertThat(answerQuestion.command.executionMode()).isEqualTo(AnswerExecutionMode.RETRY);
        assertThat(answerQuestion.command.executionAttempt()).isEqualTo(2);

        processor.process(claimed(3), NOW);
        assertThat(answerQuestion.command.executionMode()).isEqualTo(AnswerExecutionMode.RETRY);
        assertThat(answerQuestion.command.executionAttempt()).isEqualTo(3);

        processor.process(claimed(4), NOW);
        assertThat(answerQuestion.command.executionMode()).isEqualTo(AnswerExecutionMode.TERMINAL_RECONCILIATION);
        assertThat(answerQuestion.command.executionAttempt()).isEqualTo(4);
    }

    @Test
    void failsWhenNoRetryTimestampIsRepresentable() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new IllegalStateException("provider payload must not persist");
                }, BUDGET, InboxRetryPolicy.defaults());
        InboxMessage claimed = claimed(1);

        InboxProcessingOutcome outcome = processor.process(claimed, Instant.MAX);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failed).isEqualTo(claimed);
        assertThat(inbox.failedAt).isEqualTo(Instant.MAX);
        assertThat(inbox.retried).isNull();
        assertThat(inbox.transitionCount).isOne();
    }

    @Test
    void rejectsAPendingMessageBeforeAnswerExecutionOrInboxTransition() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingAnswerQuestionUseCase answerQuestion = new RecordingAnswerQuestionUseCase(result(RunOutcome.COMPLETED));
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, answerQuestion, BUDGET, InboxRetryPolicy.defaults());

        assertThatThrownBy(() -> processor.process(pending(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inbox processor accepts only processing messages");

        assertThat(answerQuestion.command).isNull();
        assertThat(inbox.transitionCount).isZero();
    }

    @Test
    void rejectsAProcessingMessageWithoutAnAttempt() {
        assertThatThrownBy(() -> claimed(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("processing inbox message must have at least one attempt");
    }

    @ParameterizedTest
    @EnumSource(FailingTransition.class)
    void letsEveryPersistenceTransitionFailureEscapeWithoutAFollowUpTransition(FailingTransition failingTransition) {
        RecordingInboxPort inbox = new RecordingInboxPort();
        inbox.failingTransition = failingTransition;
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, answerUseCaseFor(failingTransition), BUDGET, InboxRetryPolicy.defaults());
        InboxMessage claimed = failingTransition == FailingTransition.FAIL ? claimed(3) : claimed(1);

        assertThatThrownBy(() -> processor.process(claimed, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("inbox store unavailable");

        assertThat(inbox.transitionAttemptCount).isOne();
        assertThat(inbox.transitionCount).isZero();
        assertThat(inbox.completed).isNull();
        assertThat(inbox.retried).isNull();
        assertThat(inbox.failed).isNull();
    }

    @Test
    void boundsFailureDescription() {
        assertThat(new InboxFailure("ANSWER_UNEXPECTED", "x".repeat(512)).description()).hasSize(512);
        assertThatThrownBy(() -> new InboxFailure("ANSWER_UNEXPECTED", "x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsTheEnqueueCommandWithoutResolvingOrAllocatingAnIdentity() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxApplicationService service = new SessionInboxApplicationService(inbox);
        EnqueueSessionMessageCommand command = new EnqueueSessionMessageCommand(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("slack-message-1"),
                "  Preserve this exact question  ");
        InboxMessage persisted = claimed(1);
        inbox.enqueuedMessage = persisted;

        InboxMessage result = service.enqueue(command);

        assertThat(result).isEqualTo(persisted);
        assertThat(inbox.enqueuedRequest).isEqualTo(new InboxEnqueueRequest(
                command.source(), command.sourceMessageId(), command.exactQuestion()));
    }

    private static InboxMessage claimed(int attemptCount) {
        return new InboxMessage(
                new InboxMessageId("inbox-1"),
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("slack-message-1"),
                new SessionId("session-1"),
                0,
                new AnalysisRunId("run-1"),
                "  What does this method do?  ",
                InboxMessageStatus.PROCESSING,
                attemptCount,
                NOW,
                Optional.of(NOW),
                Optional.empty());
    }

    private static InboxMessage pending() {
        return new InboxMessage(
                new InboxMessageId("inbox-1"),
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("slack-message-1"),
                new SessionId("session-1"),
                0,
                new AnalysisRunId("run-1"),
                "  What does this method do?  ",
                InboxMessageStatus.PENDING,
                0,
                NOW,
                Optional.empty(),
                Optional.empty());
    }

    private static AnswerQuestionResult result(RunOutcome outcome) {
        Optional<AnswerDocument> answerDocument = outcome == RunOutcome.COMPLETED
                ? Optional.of(new AnswerDocument(List.of(new AnswerStatement(
                        new StatementId("statement-1"), StatementType.QUESTION, "Completed", Optional.empty(),
                        Set.of(), Set.of()))))
                : Optional.empty();
        RunResponseKind responseKind = answerDocument.isPresent()
                ? RunResponseKind.ANSWER
                : RunResponseKind.RUNTIME_NOTICE;
        Optional<AnswerVerificationBasis> verificationBasis = answerDocument.isPresent()
                ? Optional.of(AnswerVerificationBasis.LLM)
                : Optional.empty();
        return new AnswerQuestionResult(
                new AnalysisRunId("run-1"), outcome, "Completed", answerDocument, responseKind, verificationBasis,
                RevisionVector.empty());
    }

    private static AnswerQuestionUseCase answerUseCaseFor(FailingTransition failingTransition) {
        if (failingTransition == FailingTransition.COMPLETE) {
            return command -> result(RunOutcome.COMPLETED);
        }
        return command -> {
            throw new IllegalStateException("provider payload must not persist");
        };
    }

    private enum FailingTransition {
        COMPLETE,
        RETRY,
        FAIL
    }

    private static final class RecordingAnswerQuestionUseCase implements AnswerQuestionUseCase {

        private final AnswerQuestionResult result;
        private AnswerQuestionCommand command;
        private int invocationCount;

        private RecordingAnswerQuestionUseCase(AnswerQuestionResult result) {
            this.result = result;
        }

        @Override
        public AnswerQuestionResult answer(AnswerQuestionCommand command) {
            invocationCount++;
            this.command = command;
            return result;
        }
    }

    private static final class RecordingInboxPort implements SessionInboxPort {

        private InboxMessage completed;
        private Instant completedAt;
        private InboxMessage failed;
        private Instant failedAt;
        private InboxMessage retried;
        private Instant retriedAvailableAt;
        private InboxFailure failure;
        private FailingTransition failingTransition;
        private int transitionAttemptCount;
        private int transitionCount;
        private InboxEnqueueRequest enqueuedRequest;
        private InboxMessage enqueuedMessage;

        @Override
        public InboxMessage enqueue(InboxEnqueueRequest request) {
            enqueuedRequest = request;
            return enqueuedMessage;
        }

        @Override
        public Optional<InboxMessage> claimNext(Instant now) {
            throw new UnsupportedOperationException("processor must not claim work");
        }

        @Override
        public void complete(InboxMessage claimedMessage, Instant completedAt) {
            verifyTransition(FailingTransition.COMPLETE);
            this.completed = claimedMessage;
            this.completedAt = completedAt;
            transitionCount++;
        }

        @Override
        public void retry(InboxMessage claimedMessage, InboxFailure failure, Instant availableAt) {
            verifyTransition(FailingTransition.RETRY);
            this.retried = claimedMessage;
            this.failure = failure;
            this.retriedAvailableAt = availableAt;
            transitionCount++;
        }

        @Override
        public void fail(InboxMessage claimedMessage, InboxFailure failure, Instant failedAt) {
            verifyTransition(FailingTransition.FAIL);
            this.failed = claimedMessage;
            this.failure = failure;
            this.failedAt = failedAt;
            transitionCount++;
        }

        @Override
        public int recoverInterrupted(Instant recoveredAt) {
            throw new UnsupportedOperationException("not needed by processor tests");
        }

        private void verifyTransition(FailingTransition transition) {
            transitionAttemptCount++;
            if (failingTransition == transition) {
                throw new IllegalStateException("inbox store unavailable");
            }
        }
    }
}
