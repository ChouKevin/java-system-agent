package com.java.system.agent.answering.application;

import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeCapabilityCatalogAdapter;
import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ExecutionDeferral;
import com.java.system.agent.answering.domain.run.ExecutionDeferralReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationContractException;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.in.AnalysisExecutionDeferredException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.out.SessionPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.AnalysisAttemptIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ValidatedAgentLoop verified answer 與 terminal persistence 邊界測試
 */
class ValidatedAgentLoopAnswerTest {

    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");

    @Test
    void returnsOnlyTheVerifiedAnswerAfterTerminalEventsAreCommitted() {
        AnswerDocument document = document("Verified answer");
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        FakeSessionAdapter session = new FakeSessionAdapter();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(acceptedComplete()),
                new AgentActionProposal.Proposed(new AnswerAction(document)));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(result.responseText()).isEqualTo("Verified answer");
        assertThat(result.answerDocument()).contains(document);
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerAccepted
                        || event instanceof AgentEvent.RunConcluded)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("AnswerAccepted", "RunConcluded");
        assertThat(session.read(new SessionId("session-1")).turns())
                .singleElement()
                .extracting(conversationTurn -> conversationTurn.assistantMessage())
                .isEqualTo("Verified answer");
        assertThat(session.read(new SessionId("session-1")).turns())
                .singleElement()
                .extracting(conversationTurn -> conversationTurn.participant())
                .isEqualTo(PARTICIPANT);
    }

    @Test
    void verifierRejectionIsCarriedToRewriteAndRejectedDraftIsNotPersisted() {
        AnswerDocument rejected = document("Rejected draft");
        AnswerDocument accepted = document("Rewritten answer");
        Deque<AnswerVerdict> verdicts = new ArrayDeque<>(List.of(
                new AnswerVerdict(
                        AnswerDisposition.REJECTED,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("The answer does not address the question")),
                acceptedComplete()));
        FakeSessionAdapter session = new FakeSessionAdapter();
        ValidatedAgentLoop loop = loop(
                session,
                new RecordingTransitionPort(),
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(verdicts.removeFirst()),
                new AgentActionProposal.Proposed(new AnswerAction(rejected)),
                new AgentActionProposal.Proposed(new AnswerAction(accepted)));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.responseText()).isEqualTo("Rewritten answer");
        assertThat(session.read(new SessionId("session-1")).turns())
                .singleElement()
                .extracting(conversationTurn -> conversationTurn.assistantMessage())
                .isEqualTo("Rewritten answer");
    }

    @Test
    void resumesRejectedDurableProposalInTheSameAttemptWithoutReissuingContext() {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                new FakeSessionAdapter(),
                transitions,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    if (unavailable.getAndSet(false)) {
                        throw new AnswerVerificationUnavailableException("temporary verifier outage", null);
                    }
                    if (verificationCalls.get() == 2) {
                        return new AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                                AnswerDisposition.REJECTED, List.of(), List.of(), List.of(), List.of("rewrite")));
                    }
                    return new AnswerVerificationResult.LlmVerdict(acceptedComplete());
                },
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(
                            document(actionCalls.get() == 1 ? "Recovered answer" : "Rewritten answer")));
                });

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(AnswerExecutionUnavailableException.class);
        assertThat(transitions.events()).filteredOn(AgentEvent.AnswerProposed.class::isInstance).hasSize(1);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow()
                .pendingAnswerVerification()).isPresent();

        AgentLoopResult result = loop.execute(request());

        assertThat(result.responseText()).isEqualTo("Rewritten answer");
        assertThat(actionCalls).hasValue(2);
        assertThat(verificationCalls).hasValue(3);
        assertThat(transitions.events()).filteredOn(AgentEvent.AttemptInvalidated.class::isInstance).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.ContextIssued.class::isInstance).hasSize(1);
    }

    @Test
    void resumesPendingVerificationAfterCapacityDeferralWithoutRestartingTheAttempt() {
        Instant retryAt = Instant.parse("2026-07-28T01:02:03Z");
        ExecutionDeferral deferral = new ExecutionDeferral(retryAt, ExecutionDeferralReason.RATE_LIMITED);
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                new FakeSessionAdapter(),
                transitions,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    throw new ExternalExecutionDeferredException(deferral);
                },
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(document("Capacity-resumed answer")));
                });

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(AnalysisExecutionDeferredException.class)
                .satisfies(exception -> assertThat(((AnalysisExecutionDeferredException) exception).deferral())
                        .isSameAs(deferral));
        AgentRunState pending = transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow();
        assertThat(pending.pendingAnswerVerification()).isPresent();

        assertThatThrownBy(() -> loop.execute(capacityResumeRequest()))
                .isInstanceOf(AnalysisExecutionDeferredException.class)
                .satisfies(exception -> assertThat(((AnalysisExecutionDeferredException) exception).deferral())
                        .isSameAs(deferral));
        assertThat(actionCalls).hasValue(1);
        assertThat(verificationCalls).hasValue(2);
        assertThat(transitions.events()).filteredOn(AgentEvent.AttemptStarted.class::isInstance).hasSize(1);
        assertThat(transitions.events()).filteredOn(AgentEvent.AttemptInvalidated.class::isInstance).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.ActionAccepted.class::isInstance).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.ActionRejected.class::isInstance).isEmpty();
        AgentRunState resumed = transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow();
        assertThat(resumed.pendingAnswerVerification()).isPresent();
        assertThat(resumed.budget()).isEqualTo(pending.budget());
    }

    @Test
    void bootstrapConflictResumesTheAuthoritativePendingVerificationWithoutReissuingContext() {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        AnswerVerificationPort verifier = (mode, context) -> {
            if (unavailable.getAndSet(false)) {
                throw new AnswerVerificationUnavailableException("temporary verifier outage", null);
            }
            if (rejected.compareAndSet(false, true)) {
                return new AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                        AnswerDisposition.REJECTED, List.of(), List.of(), List.of(), List.of("rewrite")));
            }
            return new AnswerVerificationResult.LlmVerdict(acceptedComplete());
        };
        AgentActionPort actionPort = context -> {
            actionCalls.incrementAndGet();
            return new AgentActionProposal.Proposed(new AnswerAction(document(
                    actionCalls.get() == 1 ? "Durable draft" : "Rewritten answer")));
        };
        ValidatedAgentLoop initialLoop = loop(new FakeSessionAdapter(), transitions, verifier, actionPort);

        assertThatThrownBy(() -> initialLoop.execute(request()))
                .isInstanceOf(AnswerExecutionUnavailableException.class);
        transitions.hideNextReadAndRejectNextBootstrap();
        ValidatedAgentLoop recoveredLoop = loop(new FakeSessionAdapter(), transitions, verifier, actionPort);

        AgentLoopResult result = recoveredLoop.execute(request());

        assertThat(result.responseText()).isEqualTo("Rewritten answer");
        assertThat(actionCalls).hasValue(2);
        assertThat(transitions.events()).filteredOn(AgentEvent.AttemptInvalidated.class::isInstance).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.ContextIssued.class::isInstance).hasSize(1);
    }

    @Test
    void retryConflictResumesTheAuthoritativePendingVerificationWithoutRestartingAgain() {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                new FakeSessionAdapter(),
                transitions,
                (mode, context) -> {
                    if (unavailable.getAndSet(false)) {
                        throw new AnswerVerificationUnavailableException("temporary verifier outage", null);
                    }
                    if (rejected.compareAndSet(false, true)) {
                        return new AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                                AnswerDisposition.REJECTED, List.of(), List.of(), List.of(), List.of("rewrite")));
                    }
                    return new AnswerVerificationResult.LlmVerdict(acceptedComplete());
                },
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(document(
                            actionCalls.get() == 1 ? "Durable draft" : "Rewritten answer")));
                });

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(AnswerExecutionUnavailableException.class);
        AgentRunState pending = transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow();
        transitions.replace(pendingWithoutVerification(pending));
        transitions.rejectNextCommitAndReplace(pending);

        AgentLoopResult result = loop.execute(retryRequest());

        assertThat(result.responseText()).isEqualTo("Rewritten answer");
        assertThat(actionCalls).hasValue(2);
        assertThat(transitions.events()).filteredOn(AgentEvent.AttemptInvalidated.class::isInstance).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.ContextIssued.class::isInstance).hasSize(1);
    }

    @Test
    void terminalReconciliationRejectsAnAbsentRunAndFailsOrdinaryPersistedWorkWithoutExternalCalls() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                new FakeSessionAdapter(),
                transitions,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    throw new AssertionError("terminal reconciliation must not verify an ordinary run");
                },
                context -> {
                    actionCalls.incrementAndGet();
                    throw new AssertionError("terminal reconciliation must not request an action");
                });

        assertThatThrownBy(() -> loop.execute(terminalRequest()))
                .isInstanceOf(AnswerExecutionContractException.class);

        transitions.seed(runningState());
        AgentLoopResult result = loop.execute(terminalRequest());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(actionCalls).hasValue(0);
        assertThat(verificationCalls).hasValue(0);
    }

    @Test
    void terminalReconciliationTranslatesAnUnsafeConclusionConflictToItsInboundContract() {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        transitions.seed(runningState());
        transitions.rejectNextCommitAndKeepState();
        ValidatedAgentLoop loop = loop(
                new FakeSessionAdapter(),
                transitions,
                (mode, context) -> {
                    throw new AssertionError("terminal reconciliation must not verify an ordinary run");
                },
                context -> {
                    throw new AssertionError("terminal reconciliation must not request an action");
                });

        assertThatThrownBy(() -> loop.execute(terminalRequest()))
                .isInstanceOf(AnswerExecutionContractException.class)
                .hasCauseInstanceOf(AgentTransitionConflictException.class);
    }

    @Test
    void terminalReconciliationTranslatesUnsafePendingTerminalCompareAndSetToTheInboundContract() {
        AtomicBoolean failFirstAppend = new AtomicBoolean(true);
        SessionPort session = new SessionPort() {
            @Override
            public SessionHistory read(SessionId sessionId) {
                return SessionHistory.empty();
            }

            @Override
            public void append(SessionId sessionId, ConversationTurn turn) {
                if (failFirstAppend.getAndSet(false)) {
                    throw new IllegalStateException("session append interrupted");
                }
            }
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(acceptedComplete()),
                new AgentActionProposal.Proposed(new AnswerAction(document("Draft"))));

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session append interrupted");
        transitions.rejectNextCommitAndKeepState();

        assertThatThrownBy(() -> loop.execute(terminalRequest()))
                .isInstanceOf(AnswerExecutionContractException.class)
                .hasCauseInstanceOf(AgentRunInProgressException.class);
    }

    @Test
    void mapsBootstrapContextIssuanceFailureToTheInboundContractBeforePersistingOrCallingTheModel() {
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        RepositoryCatalogPort duplicateRepositories = () -> List.of(
                new RepositoryDescriptor(new RepositoryId("repo-1"), "First"),
                new RepositoryDescriptor(new RepositoryId("repo-1"), "Duplicate"));
        ValidatedAgentLoop loop = loopWithRepositories(
                transitions,
                duplicateRepositories,
                context -> {
                    actionCalls.incrementAndGet();
                    throw new AssertionError("bootstrap context contract failure must stop before model invocation");
                });

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(AnswerExecutionContractException.class);
        assertThat(actionCalls).hasValue(0);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1"))).isEmpty();
    }

    @Test
    void concludesFailedWhenRetryContextIssuanceViolatesItsContract() {
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        transitions.seed(runningState());
        RepositoryCatalogPort duplicateRepositories = () -> List.of(
                new RepositoryDescriptor(new RepositoryId("repo-1"), "First"),
                new RepositoryDescriptor(new RepositoryId("repo-1"), "Duplicate"));
        ValidatedAgentLoop loop = loopWithRepositories(
                transitions,
                duplicateRepositories,
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-2")),
                context -> {
                    actionCalls.incrementAndGet();
                    throw new AssertionError("retry context contract failure must stop before model invocation");
                });

        AgentLoopResult result = loop.execute(retryRequest());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(actionCalls).hasValue(0);
        assertThat(transitions.events())
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("AttemptInvalidated", "AttemptStarted", "RunConcluded");
    }

    @Test
    void exposesTerminalizationFailureAsCauseAndVerifierContractFailureAsSuppressed() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        FakeSessionAdapter session = new FakeSessionAdapter();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    throw new AnswerVerificationContractException("verifier returned an invalid response");
                },
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(document("Unverified draft")));
                });
        transitions.rejectNextCommitAndKeepStateAfterAnswerProposal();

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(AnswerExecutionContractException.class)
                .hasCauseInstanceOf(AgentTransitionConflictException.class)
                .satisfies(exception -> assertThat(exception.getSuppressed())
                        .singleElement()
                        .isInstanceOf(AnswerVerificationContractException.class));
        assertThat(actionCalls).hasValue(1);
        assertThat(verificationCalls).hasValue(1);
        assertThat(session.read(new SessionId("session-1")).turns()).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.AnswerAccepted.class::isInstance).isEmpty();
    }

    @Test
    void terminalReconciliationAbandonsPendingVerificationThenFailsWithoutExternalWork() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        FakeSessionAdapter session = new FakeSessionAdapter();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    throw new AnswerVerificationUnavailableException("temporary verifier outage", null);
                },
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(document("Draft")));
                });

        assertThatThrownBy(() -> loop.execute(request())).isInstanceOf(AnswerExecutionUnavailableException.class);

        AgentLoopResult result = loop.execute(terminalRequest());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(actionCalls).hasValue(1);
        assertThat(verificationCalls).hasValue(1);
        assertThat(session.read(new SessionId("session-1")).turns()).isEmpty();
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerVerificationAbandoned
                        || event instanceof AgentEvent.RunConcluded)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("AnswerVerificationAbandoned", "RunConcluded");
    }

    @Test
    void abandonsAnIncompatibleVerificationResultAndConcludesFailed() {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                new FakeSessionAdapter(),
                transitions,
                AnswerVerificationMode.CONTRACT_ONLY,
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(acceptedComplete()),
                context -> new AgentActionProposal.Proposed(new AnswerAction(document("Contract answer"))));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(transitions.events()).filteredOn(AgentEvent.AnswerVerificationAbandoned.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events()).filteredOn(AgentEvent.RunConcluded.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEvent.RunConcluded) event).outcome()).isEqualTo(RunOutcome.FAILED));
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow()
                .pendingAnswerVerification()).isEmpty();
    }

    @Test
    void propagatesSessionAppendFailureAfterPersistingTerminalAcceptanceForResume() {
        SessionPort failingSession = new SessionPort() {
            @Override
            public SessionHistory read(SessionId sessionId) {
                return SessionHistory.empty();
            }

            @Override
            public void append(SessionId sessionId, ConversationTurn turn) {
                throw new IllegalStateException("session unavailable");
            }
        };
        ValidatedAgentLoop loop = loop(
                failingSession,
                new RecordingTransitionPort(),
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(acceptedComplete()),
                new AgentActionProposal.Proposed(new AnswerAction(document("Draft"))));

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session unavailable");
    }

    @Test
    void retriesThePersistedAcceptedAnswerAfterConclusionCommitFailureWithoutCallingExternalReasoningAgain() {
        AnswerDocument document = document("Recovered answer");
        FakeSessionAdapter session = new FakeSessionAdapter();
        FailingConclusionTransitionPort transitions = new FailingConclusionTransitionPort();
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    return new AnswerVerificationResult.LlmVerdict(acceptedComplete());
                },
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(document));
                });

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(AgentLoopException.class)
                .hasMessageContaining("transition could not be committed");

        assertThatThrownBy(() -> loop.execute(request(new SessionId("session-2"), "Another question")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request identity");

        AgentLoopResult recovered = loop.execute(request());
        AgentLoopResult replayed = loop.execute(request());

        assertThatThrownBy(() -> loop.execute(request(new SessionId("session-2"), "Another question")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request identity");

        assertThat(recovered).isEqualTo(replayed);
        assertThat(recovered.responseText()).isEqualTo("Recovered answer");
        assertThat(recovered.answerDocument()).contains(document);
        assertThat(actionCalls).hasValue(1);
        assertThat(verificationCalls).hasValue(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AnswerAccepted.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.RunConcluded.class::isInstance)
                .hasSize(1);
        assertThat(session.read(new SessionId("session-1")).turns()).hasSize(1);
    }

    @Test
    void rejectsParticipantDriftBeforeExternalExecutionForEveryPersistedResumeMode() {
        for (AnswerExecutionMode mode : List.of(
                AnswerExecutionMode.RETRY,
                AnswerExecutionMode.CAPACITY_RESUME,
                AnswerExecutionMode.TERMINAL_RECONCILIATION)) {
            AtomicInteger actionCalls = new AtomicInteger();
            AtomicInteger verificationCalls = new AtomicInteger();
            ValidatedAgentLoop loop = loop(
                    new FakeSessionAdapter(),
                    new FailingConclusionTransitionPort(),
                    (verificationMode, context) -> {
                        verificationCalls.incrementAndGet();
                        return new AnswerVerificationResult.LlmVerdict(acceptedComplete());
                    },
                    context -> {
                        actionCalls.incrementAndGet();
                        return new AgentActionProposal.Proposed(new AnswerAction(document("Recovered answer")));
                    });

            assertThatThrownBy(() -> loop.execute(request())).isInstanceOf(AgentLoopException.class);

            assertThatThrownBy(() -> loop.execute(request(new ParticipantRef("test", "participant-2"), mode)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("request identity");
            assertThat(actionCalls).hasValue(1);
            assertThat(verificationCalls).hasValue(1);
        }
    }

    @Test
    void cancellationDuringVerificationPreventsAcceptedTerminalPersistenceAndSessionAppend() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger verificationCalls = new AtomicInteger();
        FakeSessionAdapter session = new FakeSessionAdapter();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    cancelled.set(true);
                    return new AnswerVerificationResult.LlmVerdict(acceptedComplete());
                },
                context -> new AgentActionProposal.Proposed(new AnswerAction(document("Cancelled answer"))),
                runId -> cancelled.get());

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(verificationCalls).hasValue(1);
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerAccepted
                        || event instanceof AgentEvent.ClarificationAccepted)
                .isEmpty();
        assertThat(session.read(new SessionId("session-1")).turns()).isEmpty();
        assertThat(transitions.events())
                .filteredOn(AgentEvent.RunConcluded.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEvent.RunConcluded) event).outcome())
                        .isEqualTo(RunOutcome.CANCELLED));
    }

    @Test
    void terminalAnswerAcceptanceCancelledByTheStoreDoesNotAppendSession() {
        FakeSessionAdapter session = new FakeSessionAdapter();
        TerminalCancellationTransitionPort transitions = new TerminalCancellationTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(acceptedComplete()),
                new AgentActionProposal.Proposed(new AnswerAction(document("Cancelled at commit"))));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(session.read(new SessionId("session-1")).turns()).isEmpty();
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerAccepted
                        || event instanceof AgentEvent.ClarificationAccepted)
                .isEmpty();
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerVerificationAbandoned
                        || event instanceof AgentEvent.RunConcluded)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("AnswerVerificationAbandoned", "RunConcluded");
    }

    @Test
    void terminalClarificationAcceptanceCancelledByTheStoreDoesNotAppendSession() {
        FakeSessionAdapter session = new FakeSessionAdapter();
        TerminalCancellationTransitionPort transitions = new TerminalCancellationTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                (mode, context) -> {
                    throw new AssertionError("clarification must not invoke answer verification");
                },
                new AgentActionProposal.Proposed(
                        new ClarifyAction("Which repository?", List.of(), "Need scope")));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(session.read(new SessionId("session-1")).turns()).isEmpty();
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerAccepted
                        || event instanceof AgentEvent.ClarificationAccepted)
                .isEmpty();
    }

    private ValidatedAgentLoop loop(
            SessionPort session,
            AgentTransitionPort transitions,
            AnswerVerificationPort verifier,
            AgentActionProposal... proposals) {
        Deque<AgentActionProposal> actions = new ArrayDeque<>(List.of(proposals));
        return loop(session, transitions, verifier, context -> actions.removeFirst());
    }

    private ValidatedAgentLoop loop(
            SessionPort session,
            AgentTransitionPort transitions,
            AnswerVerificationPort verifier,
            AgentActionPort actionPort) {
        return loop(session, transitions, AnswerVerificationMode.LLM, verifier, actionPort,
                new FakeCancellationAdapter());
    }

    private ValidatedAgentLoop loop(
            SessionPort session,
            AgentTransitionPort transitions,
            AnswerVerificationMode verificationMode,
            AnswerVerificationPort verifier,
            AgentActionPort actionPort) {
        return loop(session, transitions, verificationMode, verifier, actionPort, new FakeCancellationAdapter());
    }

    private ValidatedAgentLoop loop(
            SessionPort session,
            AgentTransitionPort transitions,
            AnswerVerificationPort verifier,
            AgentActionPort actionPort,
            AnalysisCancellationPort cancellationPort) {
        return loop(session, transitions, AnswerVerificationMode.LLM, verifier, actionPort, cancellationPort);
    }

    private ValidatedAgentLoop loop(
            SessionPort session,
            AgentTransitionPort transitions,
            AnswerVerificationMode verificationMode,
            AnswerVerificationPort verifier,
            AgentActionPort actionPort,
            AnalysisCancellationPort cancellationPort) {
        return new ValidatedAgentLoop(
                actionPort,
                query -> {
                    throw new AssertionError("answer test must not execute a semantic query");
                },
                verifier,
                verificationMode,
                session,
                new FakeRepositoryCatalogAdapter(),
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("unused")),
                cancellationPort,
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
    }

    private ValidatedAgentLoop loopWithRepositories(
            AgentTransitionPort transitions,
            RepositoryCatalogPort repositories,
            AgentActionPort actionPort) {
        return loopWithRepositories(
                transitions,
                repositories,
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                actionPort);
    }

    private ValidatedAgentLoop loopWithRepositories(
            AgentTransitionPort transitions,
            RepositoryCatalogPort repositories,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            AgentActionPort actionPort) {
        return new ValidatedAgentLoop(
                actionPort,
                query -> {
                    throw new AssertionError("bootstrap contract test must not execute a semantic query");
                },
                (mode, context) -> {
                    throw new AssertionError("bootstrap contract test must not verify an answer");
                },
                AnswerVerificationMode.LLM,
                new FakeSessionAdapter(),
                repositories,
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("unused")),
                new FakeCancellationAdapter(),
                attemptIdGenerator,
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
    }

    private AgentLoopRequest request() {
        return request(new SessionId("session-1"), "What is verified?");
    }

    private AgentLoopRequest request(SessionId sessionId, String question) {
        return new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                sessionId,
                PARTICIPANT,
                question,
                new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0));
    }

    private AgentLoopRequest request(ParticipantRef participant, AnswerExecutionMode mode) {
        int attemptCount = mode == AnswerExecutionMode.TERMINAL_RECONCILIATION ? 4 : 2;
        return new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                participant,
                "What is verified?",
                new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0),
                mode,
                attemptCount);
    }

    private AgentLoopRequest terminalRequest() {
        return new AgentLoopRequest(new AnalysisRunId("run-1"), new SessionId("session-1"), PARTICIPANT,
                "What is verified?", new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0),
                AnswerExecutionMode.TERMINAL_RECONCILIATION, 4);
    }

    private AgentLoopRequest retryRequest() {
        return new AgentLoopRequest(new AnalysisRunId("run-1"), new SessionId("session-1"), PARTICIPANT,
                "What is verified?", new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0),
                AnswerExecutionMode.RETRY, 2);
    }

    private AgentLoopRequest capacityResumeRequest() {
        return new AgentLoopRequest(new AnalysisRunId("run-1"), new SessionId("session-1"), PARTICIPANT,
                "What is verified?", new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0),
                AnswerExecutionMode.CAPACITY_RESUME, 2);
    }

    private AgentRunState runningState() {
        AgentRunState initial = AgentRunState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0),
                new com.java.system.agent.answering.domain.run.RunRequestIdentity(
                        "session-1", PARTICIPANT, "What is verified?"));
        AgentStateReducer reducer = new AgentStateReducer();
        AgentRunState started = reducer.reduce(initial,
                new AgentEvent.RunStarted(initial.runId(), initial.currentAttempt().attemptId(), 0)).candidateState();
        AgentRunState attempted = reducer.reduce(started,
                new AgentEvent.AttemptStarted(started.runId(), started.currentAttempt().attemptId(), 1,
                        started.currentAttempt())).candidateState();
        return reducer.reduce(attempted,
                new AgentEvent.ContextIssued(attempted.runId(), attempted.currentAttempt().attemptId(), 2,
                        attempted.currentAttempt().revisionVector(), Map.of(), Map.of(), Map.of(), Map.of()))
                .candidateState();
    }

    private AgentRunState pendingWithoutVerification(AgentRunState state) {
        return new AgentRunState(
                state.runId(),
                AgentRunStatus.RUNNING,
                state.currentAttempt(),
                state.attemptSequence(),
                state.budget(),
                state.acceptedActionCount(),
                state.rejectedActionCount(),
                state.stateRevision(),
                state.finalOutcome(),
                state.runtimeNoticeReason(),
                state.failureReason(),
                state.pendingTerminalResponse(),
                Optional.empty(),
                state.requestIdentity());
    }

    private AnswerDocument document(String text) {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"),
                StatementType.QUESTION,
                text,
                Optional.empty(),
                Set.of(),
                Set.of())));
    }

    private AnswerVerdict acceptedComplete() {
        return new AnswerVerdict(
                AnswerDisposition.ACCEPTED_COMPLETE,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {

        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();
        private boolean hideNextRead;
        private boolean rejectNextBootstrap;
        private boolean rejectNextCommit;
        private boolean rejectCommitAfterAnswerProposal;
        private Optional<AgentRunState> replacementOnRejectedCommit = Optional.empty();

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            if (rejectNextBootstrap) {
                rejectNextBootstrap = false;
                throw new AgentTransitionConflictException("bootstrap conflicted with authoritative state");
            }
            AgentRunState existing = states.get(bootstrap.finalTransition().candidateState().runId());
            if (Objects.nonNull(existing)) {
                throw new AgentTransitionConflictException("run already exists");
            }
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            states.put(bootstrap.finalTransition().candidateState().runId(), bootstrap.finalTransition().candidateState());
            return bootstrap.finalTransition().candidateState();
        }

        @Override
        public synchronized AgentRunState commit(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (transition.event() instanceof AgentEvent.RunStarted
                    || transition.event() instanceof AgentEvent.AttemptStarted && transition.event().expectedStateRevision() == 1) {
                throw new IllegalArgumentException("bootstrap events require an atomic bootstrap commit");
            }
            if (Objects.isNull(existing)
                    || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            if (rejectNextCommit) {
                rejectNextCommit = false;
                replacementOnRejectedCommit.ifPresent(replacement -> states.put(replacement.runId(), replacement));
                replacementOnRejectedCommit = Optional.empty();
                throw new AgentTransitionConflictException("commit conflicted with authoritative state");
            }
            if (rejectCommitAfterAnswerProposal
                    && transition.event() instanceof AgentEvent.AnswerVerificationAbandoned) {
                rejectCommitAfterAnswerProposal = false;
                throw new AgentTransitionConflictException("abandon conflicted with authoritative state");
            }
            events.add(transition.event());
            states.put(transition.candidateState().runId(), transition.candidateState());
            return transition.candidateState();
        }

        @Override
        public synchronized AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return commit(transition);
        }

        @Override
        public synchronized Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            if (hideNextRead) {
                hideNextRead = false;
                return Optional.empty();
            }
            return Optional.ofNullable(states.get(runId));
        }

        private synchronized void seed(AgentRunState state) {
            states.put(state.runId(), state);
        }

        private synchronized void replace(AgentRunState state) {
            states.put(state.runId(), state);
        }

        private synchronized void hideNextReadAndRejectNextBootstrap() {
            hideNextRead = true;
            rejectNextBootstrap = true;
        }

        private synchronized void rejectNextCommitAndReplace(AgentRunState state) {
            rejectNextCommit = true;
            replacementOnRejectedCommit = Optional.of(state);
        }

        private synchronized void rejectNextCommitAndKeepState() {
            rejectNextCommit = true;
        }

        private synchronized void rejectNextCommitAndKeepStateAfterAnswerProposal() {
            rejectCommitAfterAnswerProposal = true;
        }

        private List<AgentEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class FailingConclusionTransitionPort implements AgentTransitionPort {

        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();
        private final AtomicBoolean failFirstConclusion = new AtomicBoolean(true);

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState existing = states.get(bootstrap.finalTransition().candidateState().runId());
            if (Objects.nonNull(existing)) {
                throw new AgentTransitionConflictException("run already exists");
            }
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            states.put(bootstrap.finalTransition().candidateState().runId(), bootstrap.finalTransition().candidateState());
            return bootstrap.finalTransition().candidateState();
        }

        @Override
        public synchronized AgentRunState commit(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (transition.event() instanceof AgentEvent.RunStarted
                    || transition.event() instanceof AgentEvent.AttemptStarted && transition.event().expectedStateRevision() == 1) {
                throw new IllegalArgumentException("bootstrap events require an atomic bootstrap commit");
            }
            if (Objects.isNull(existing)
                    || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            if (transition.event() instanceof AgentEvent.RunConcluded && failFirstConclusion.getAndSet(false)) {
                throw new IllegalStateException("conclusion persistence unavailable");
            }
            events.add(transition.event());
            states.put(transition.candidateState().runId(), transition.candidateState());
            return transition.candidateState();
        }

        @Override
        public synchronized AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return commit(transition);
        }

        @Override
        public synchronized Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private List<AgentEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class TerminalCancellationTransitionPort implements AgentTransitionPort {

        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState existing = states.get(bootstrap.finalTransition().candidateState().runId());
            if (Objects.nonNull(existing)) {
                throw new AgentTransitionConflictException("run already exists");
            }
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            states.put(bootstrap.finalTransition().candidateState().runId(), bootstrap.finalTransition().candidateState());
            return bootstrap.finalTransition().candidateState();
        }

        @Override
        public synchronized AgentRunState commit(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (transition.event() instanceof AgentEvent.RunStarted
                    || transition.event() instanceof AgentEvent.AttemptStarted && transition.event().expectedStateRevision() == 1) {
                throw new IllegalArgumentException("bootstrap events require an atomic bootstrap commit");
            }
            if (Objects.isNull(existing)
                    || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            events.add(transition.event());
            states.put(transition.candidateState().runId(), transition.candidateState());
            return transition.candidateState();
        }

        @Override
        public synchronized AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            throw new TerminalAcceptanceCancelledException("durable cancellation won terminal arbitration");
        }

        @Override
        public synchronized Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private List<AgentEvent> events() {
            return List.copyOf(events);
        }
    }
}
