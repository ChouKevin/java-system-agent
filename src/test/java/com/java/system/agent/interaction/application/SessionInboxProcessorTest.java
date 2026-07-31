package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxDeferReason;
import com.java.system.agent.interaction.domain.InboxFailure;
import com.java.system.agent.interaction.domain.FinalInteractionResponse;
import com.java.system.agent.interaction.domain.InboxMessage;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.InboxMessageStatus;
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.port.out.SessionInboxPort;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ExecutionDeferral;
import com.java.system.agent.answering.domain.run.ExecutionDeferralReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnalysisExecutionDeferredException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.in.AnswerQuestionCommand;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;
import com.java.system.agent.answering.port.in.AnswerQuestionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SessionInboxProcessor 對 M3 claim 與 terminal delivery transition 的 application 邊界測試
 */
class SessionInboxProcessorTest {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");
    private static final AttemptBudget BUDGET = new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0);

    @ParameterizedTest
    @MethodSource("validTerminalResults")
    void persistsEveryValidTerminalPairWithTheClaimParticipantAndEstablishedText(AnswerQuestionResult result) {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingUseCase useCase = new RecordingUseCase(result);
        SessionInboxProcessor processor = new SessionInboxProcessor(inbox, useCase, BUDGET, InboxRetryPolicy.defaults());
        InboxClaim claim = claim(1, Optional.empty());

        InboxProcessingOutcome outcome = processor.process(claim, NOW);

        if (result.responseKind() == RunResponseKind.RUNTIME_NOTICE && result.outcome() == RunOutcome.FAILED) {
            assertThat(outcome).isEqualTo(InboxProcessingOutcome.FAILED);
            assertThat(inbox.failedClaim).isEqualTo(claim);
            assertThat(inbox.safeResponseText).isEqualTo("處理失敗，請稍後再試");
        } else {
            assertThat(outcome).isEqualTo(InboxProcessingOutcome.COMPLETED);
            assertThat(inbox.completedClaim).isEqualTo(claim);
            assertThat(inbox.completedResult).isEqualTo(expectedCompletedResult(result));
        }
        assertThat(useCase.command).isEqualTo(new AnswerQuestionCommand(
                claim.message().runId(), claim.message().sessionId(), claim.message().participant(),
                claim.message().questionText(), BUDGET, AnswerExecutionMode.INITIAL, 1));
    }

    @Test
    void replacesDiagnosticCancellationTextWithTheFixedSafeResponseWithoutChangingItsTerminalPair() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        AnswerQuestionResult runtimeCancellation = result(
                RunResponseKind.RUNTIME_NOTICE, RunOutcome.CANCELLED, "provider diagnostic: internal trace");
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, new RecordingUseCase(runtimeCancellation), BUDGET, InboxRetryPolicy.defaults());

        InboxProcessingOutcome outcome = processor.process(claim(1, Optional.empty()), NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(inbox.completedResult).isEqualTo(new FinalInteractionResponse(
                runtimeCancellation.runId(), RunOutcome.CANCELLED, RunResponseKind.RUNTIME_NOTICE, "處理已取消"));
    }

    @ParameterizedTest
    @MethodSource("capacityClaims")
    void defersModelCapacityWithoutChangingTheClaimedAttempt(Optional<InboxDeferReason> deferReason) {
        RecordingInboxPort inbox = new RecordingInboxPort();
        AnswerQuestionUseCase useCase = command -> {
            throw new AnalysisExecutionDeferredException(new ExecutionDeferral(
                    NOW.plusSeconds(30), ExecutionDeferralReason.RATE_LIMITED));
        };
        SessionInboxProcessor processor = new SessionInboxProcessor(inbox, useCase, BUDGET, InboxRetryPolicy.defaults());
        InboxClaim claim = claim(2, deferReason);

        InboxProcessingOutcome outcome = processor.process(claim, NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.CAPACITY_DEFERRED);
        assertThat(inbox.capacityClaim).isEqualTo(claim);
        assertThat(inbox.capacityRetryAt).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void schedulesTerminalReconciliationAfterTheRetryCeilingAndUsesThePersistedAttempt() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new IllegalStateException("provider payload must not persist");
                }, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(1, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.retryAvailableAt).isEqualTo(NOW.plusSeconds(1));
        assertThat(processor.process(claim(2, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.retryAvailableAt).isEqualTo(NOW.plusSeconds(2));
        assertThat(processor.process(claim(3, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.retryAvailableAt).isEqualTo(NOW);

        RecordingUseCase reconciliation = new RecordingUseCase(
                result(RunResponseKind.RUNTIME_NOTICE, RunOutcome.INCONCLUSIVE, "durable terminal result"));
        SessionInboxProcessor reconciliationProcessor = new SessionInboxProcessor(
                inbox, reconciliation, BUDGET, InboxRetryPolicy.defaults());

        assertThat(reconciliationProcessor.process(claim(4, Optional.empty()), NOW))
                .isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(reconciliation.command.executionMode()).isEqualTo(AnswerExecutionMode.TERMINAL_RECONCILIATION);
        assertThat(reconciliation.command.executionAttempt()).isEqualTo(4);
    }

    @Test
    void selectsCapacityResumeBeforeOrdinaryRetryForAnUnchangedClaimedAttempt() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingUseCase useCase = new RecordingUseCase(
                result(RunResponseKind.RUNTIME_NOTICE, RunOutcome.INCONCLUSIVE, "capacity resumed"));
        SessionInboxProcessor processor = new SessionInboxProcessor(inbox, useCase, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(2, Optional.of(InboxDeferReason.MODEL_CAPACITY)), NOW))
                .isEqualTo(InboxProcessingOutcome.COMPLETED);

        assertThat(useCase.command.executionMode()).isEqualTo(AnswerExecutionMode.CAPACITY_RESUME);
        assertThat(useCase.command.executionAttempt()).isEqualTo(2);
    }

    @Test
    void selectsTerminalReconciliationBeforeCapacityResumeForAStaleCapacityReason() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        RecordingUseCase useCase = new RecordingUseCase(
                result(RunResponseKind.RUNTIME_NOTICE, RunOutcome.INCONCLUSIVE, "reconciled"));
        SessionInboxProcessor processor = new SessionInboxProcessor(inbox, useCase, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(4, Optional.of(InboxDeferReason.MODEL_CAPACITY)), NOW))
                .isEqualTo(InboxProcessingOutcome.COMPLETED);

        assertThat(useCase.command.executionMode()).isEqualTo(AnswerExecutionMode.TERMINAL_RECONCILIATION);
    }

    @Test
    void retriesUnavailableVerifierWithBoundedFailureAndFailsTerminalReconciliation() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new AnswerExecutionUnavailableException(
                            "provider payload must not persist", new IllegalStateException("connection refused"));
                }, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(1, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.failure.code()).isEqualTo("ANSWER_VERIFIER_UNAVAILABLE");
        assertThat(inbox.failure.description()).doesNotContain("provider payload", "connection refused");

        assertThat(processor.process(claim(4, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failedClaim).isEqualTo(claim(4, Optional.empty()));
        assertThat(inbox.safeResponseText).isEqualTo("處理失敗，請稍後再試");
    }

    @Test
    void failsContractViolationWithoutRetryAndNeverPersistsProviderText() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new AnswerExecutionContractException("provider response violated the contract");
                }, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(1, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failedClaim).isEqualTo(claim(1, Optional.empty()));
        assertThat(inbox.retriedClaim).isNull();
        assertThat(inbox.failure.code()).isEqualTo("ANSWER_INTEGRATION_CONTRACT");
        assertThat(inbox.failure.description()).doesNotContain("provider response");
    }

    @Test
    void mapsPlanningToolContractViolationToItsDedicatedInboxFailureWithoutRetry() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox,
                command -> {
                    throw new AnswerExecutionContractException(
                            AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT,
                            "planning tool contract failed",
                            new IllegalStateException("provider payload must not persist"));
                },
                BUDGET,
                InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(1, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.FAILED);

        assertThat(inbox.failedClaim).isEqualTo(claim(1, Optional.empty()));
        assertThat(inbox.failure).isEqualTo(InboxFailure.PLANNING_TOOL_CONTRACT);
        assertThat(inbox.retriedClaim).isNull();
        assertThat(inbox.transitionCount).isOne();
    }

    @Test
    void mapsHttpMutationContractViolationToAFinalSafeFailureWithoutRetry() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        String secretUrl = "https://secret.example.invalid/mutate";
        String secretBody = "{\"credential\":\"secret-body\"}";
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox,
                command -> {
                    throw new AnswerExecutionContractException(
                            AnswerExecutionContractFailure.HTTP_MUTATION_CONTRACT,
                            "HTTP mutation contract failed",
                            new IllegalStateException(secretUrl + secretBody));
                },
                BUDGET,
                InboxRetryPolicy.defaults());

        InboxProcessingOutcome outcome = processor.process(claim(1, Optional.empty()), NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failedClaim).isEqualTo(claim(1, Optional.empty()));
        assertThat(inbox.failure).isEqualTo(InboxFailure.HTTP_MUTATION_CONTRACT);
        assertThat(inbox.safeResponseText).isEqualTo("處理失敗，請稍後再試").doesNotContain(secretUrl, secretBody);
        assertThat(inbox.retriedClaim).isNull();
        assertThat(inbox.retryAvailableAt).isNull();
        assertThat(inbox.transitionCount).isOne();
    }

    @Test
    void treatsANullAnswerResultAsRetryableInfrastructureFailure() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(inbox, command -> null, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(1, Optional.empty()), NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(inbox.retriedClaim).isEqualTo(claim(1, Optional.empty()));
        assertThat(inbox.retryAvailableAt).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void failsWhenRetryTimestampCannotBeRepresented() {
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, command -> {
                    throw new IllegalStateException("provider payload must not persist");
                }, BUDGET, InboxRetryPolicy.defaults());

        assertThat(processor.process(claim(1, Optional.empty()), Instant.MAX)).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failedClaim).isEqualTo(claim(1, Optional.empty()));
        assertThat(inbox.failedAt).isEqualTo(Instant.MAX);
        assertThat(inbox.retriedClaim).isNull();
    }

    @ParameterizedTest
    @EnumSource(FailingTransition.class)
    void letsPersistenceTransitionFailuresEscapeWithoutAFollowUpTransition(FailingTransition transition) {
        RecordingInboxPort inbox = new RecordingInboxPort();
        inbox.failingTransition = transition;
        AnswerQuestionUseCase useCase = useCaseFor(transition);
        SessionInboxProcessor processor = new SessionInboxProcessor(inbox, useCase, BUDGET, InboxRetryPolicy.defaults());
        InboxClaim claim = transition == FailingTransition.FAIL ? claim(4, Optional.empty()) : claim(1, Optional.empty());

        assertThatThrownBy(() -> processor.process(claim, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("inbox store unavailable");
        assertThat(inbox.transitionAttemptCount).isOne();
        assertThat(inbox.transitionCount).isZero();
    }

    private static Stream<AnswerQuestionResult> validTerminalResults() {
        return Stream.of(
                answer(RunOutcome.COMPLETED),
                answer(RunOutcome.INCONCLUSIVE),
                result(RunResponseKind.CLARIFICATION, RunOutcome.INCONCLUSIVE, "請提供 repository"),
                result(RunResponseKind.RUNTIME_NOTICE, RunOutcome.INCONCLUSIVE, "資訊不足"),
                result(RunResponseKind.RUNTIME_NOTICE, RunOutcome.FAILED, "unsafe provider payload"),
                result(RunResponseKind.RUNTIME_NOTICE, RunOutcome.CANCELLED, "分析已取消"));
    }

    private static Stream<Optional<InboxDeferReason>> capacityClaims() {
        return Stream.of(Optional.empty(), Optional.of(InboxDeferReason.MODEL_CAPACITY));
    }

    private static AnswerQuestionUseCase useCaseFor(FailingTransition transition) {
        return switch (transition) {
            case COMPLETE -> command -> answer(RunOutcome.COMPLETED);
            case DEFER -> command -> {
                throw new AnalysisExecutionDeferredException(new ExecutionDeferral(
                        NOW.plusSeconds(30), ExecutionDeferralReason.RATE_LIMITED));
            };
            case RETRY, FAIL -> command -> {
                throw new IllegalStateException("provider payload must not persist");
            };
        };
    }

    private static AnswerQuestionResult answer(RunOutcome outcome) {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "答案", Optional.empty(), Set.of(), Set.of())));
        return new AnswerQuestionResult(
                new AnalysisRunId("run-1"), outcome, document.renderParagraphs(), Optional.of(document),
                RunResponseKind.ANSWER, Optional.of(AnswerVerificationBasis.LLM), RevisionVector.empty());
    }

    private static AnswerQuestionResult result(RunResponseKind kind, RunOutcome outcome, String text) {
        return new AnswerQuestionResult(
                new AnalysisRunId("run-1"), outcome, text, Optional.empty(), kind, Optional.empty(), RevisionVector.empty());
    }

    private static FinalInteractionResponse expectedCompletedResult(AnswerQuestionResult result) {
        String responseText = result.responseKind() == RunResponseKind.RUNTIME_NOTICE
                && result.outcome() == RunOutcome.CANCELLED
                ? "處理已取消"
                : result.responseText();
        return new FinalInteractionResponse(result.runId(), result.outcome(), result.responseKind(), responseText);
    }

    private static InboxClaim claim(int attemptCount, Optional<InboxDeferReason> deferReason) {
        InboxMessage message = new InboxMessage(
                new InboxMessageId("inbox-1"), new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"), new SessionId("session-1"), 0, new AnalysisRunId("run-1"),
                new ParticipantRef("slack", "U123456"), "<@bot> 問題", "問題", InboxMessageStatus.PROCESSING,
                attemptCount, NOW, Optional.of(NOW), deferReason, Optional.empty());
        return new InboxClaim(message);
    }

    private static final class RecordingUseCase implements AnswerQuestionUseCase {

        private final AnswerQuestionResult result;
        private AnswerQuestionCommand command;

        private RecordingUseCase(AnswerQuestionResult result) {
            this.result = result;
        }

        @Override
        public AnswerQuestionResult answer(AnswerQuestionCommand command) {
            this.command = command;
            return result;
        }
    }

    private static final class RecordingInboxPort implements SessionInboxPort {

        private InboxClaim completedClaim;
        private FinalInteractionResponse completedResult;
        private InboxClaim failedClaim;
        private String safeResponseText;
        private InboxClaim capacityClaim;
        private Instant capacityRetryAt;
        private InboxClaim retriedClaim;
        private Instant retryAvailableAt;
        private InboxFailure failure;
        private Instant failedAt;
        private FailingTransition failingTransition;
        private int transitionAttemptCount;
        private int transitionCount;

        @Override
        public Optional<InboxClaim> claimNext(Instant now) {
            throw new UnsupportedOperationException("processor must not claim work");
        }

        @Override
        public void completeWithFinal(InboxClaim claim, FinalInteractionResponse result, Instant completedAt) {
            verifyTransition(FailingTransition.COMPLETE);
            completedClaim = claim;
            completedResult = result;
            transitionCount++;
        }

        @Override
        public void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt) {
            verifyTransition(FailingTransition.FAIL);
            failedClaim = claim;
            this.safeResponseText = safeResponseText;
            this.failure = failure;
            this.failedAt = failedAt;
            transitionCount++;
        }

        @Override
        public void retry(InboxClaim claim, InboxFailure failure, Instant availableAt) {
            verifyTransition(FailingTransition.RETRY);
            retriedClaim = claim;
            retryAvailableAt = availableAt;
            this.failure = failure;
            transitionCount++;
        }

        @Override
        public void deferForCapacity(InboxClaim claim, Instant retryAt) {
            verifyTransition(FailingTransition.DEFER);
            capacityClaim = claim;
            capacityRetryAt = retryAt;
            transitionCount++;
        }

        @Override
        public int recoverInterrupted(Instant recoveredAt) {
            throw new UnsupportedOperationException("not used by this test");
        }

        private void verifyTransition(FailingTransition transition) {
            transitionAttemptCount++;
            if (failingTransition == transition) {
                throw new IllegalStateException("inbox store unavailable");
            }
        }
    }

    private enum FailingTransition {
        COMPLETE,
        RETRY,
        FAIL,
        DEFER
    }
}
