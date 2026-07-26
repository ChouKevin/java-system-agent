package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.runtime.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeCapabilityCatalogAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.runtime.application.state.AgentStateReducer;
import com.java.system.agent.runtime.application.state.AgentTransitionCommitter;
import com.java.system.agent.runtime.application.validation.AgentActionValidator;
import com.java.system.agent.runtime.application.validation.AnswerDocumentValidator;
import com.java.system.agent.runtime.application.validation.AnswerVerdictValidator;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentBootstrap;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentTransition;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
import com.java.system.agent.runtime.port.out.AgentTransitionPort;
import com.java.system.agent.runtime.port.out.AgentTransitionConflictException;
import com.java.system.agent.runtime.port.out.TerminalAcceptanceCancelledException;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import com.java.system.agent.runtime.port.out.SessionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
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

    @Test
    void returnsOnlyTheVerifiedAnswerAfterTerminalEventsAreCommitted() {
        AnswerDocument document = document("Verified answer");
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        FakeSessionAdapter session = new FakeSessionAdapter();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                context -> acceptedComplete(),
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
                .extracting(ConversationTurn::assistantMessage)
                .isEqualTo("Verified answer");
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
                context -> verdicts.removeFirst(),
                new AgentActionProposal.Proposed(new AnswerAction(rejected)),
                new AgentActionProposal.Proposed(new AnswerAction(accepted)));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.responseText()).isEqualTo("Rewritten answer");
        assertThat(session.read(new SessionId("session-1")).turns())
                .singleElement()
                .extracting(ConversationTurn::assistantMessage)
                .isEqualTo("Rewritten answer");
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
                context -> acceptedComplete(),
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
                context -> {
                    verificationCalls.incrementAndGet();
                    return acceptedComplete();
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
    void cancellationDuringVerificationPreventsAcceptedTerminalPersistenceAndSessionAppend() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger verificationCalls = new AtomicInteger();
        FakeSessionAdapter session = new FakeSessionAdapter();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                context -> {
                    verificationCalls.incrementAndGet();
                    cancelled.set(true);
                    return acceptedComplete();
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
                context -> acceptedComplete(),
                new AgentActionProposal.Proposed(new AnswerAction(document("Cancelled at commit"))));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(session.read(new SessionId("session-1")).turns()).isEmpty();
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerAccepted
                        || event instanceof AgentEvent.ClarificationAccepted)
                .isEmpty();
    }

    @Test
    void terminalClarificationAcceptanceCancelledByTheStoreDoesNotAppendSession() {
        FakeSessionAdapter session = new FakeSessionAdapter();
        TerminalCancellationTransitionPort transitions = new TerminalCancellationTransitionPort();
        ValidatedAgentLoop loop = loop(
                session,
                transitions,
                context -> {
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
        return loop(session, transitions, verifier, actionPort, new FakeCancellationAdapter());
    }

    private ValidatedAgentLoop loop(
            SessionPort session,
            AgentTransitionPort transitions,
            AnswerVerificationPort verifier,
            AgentActionPort actionPort,
            AnalysisCancellationPort cancellationPort) {
        return new ValidatedAgentLoop(
                actionPort,
                query -> {
                    throw new AssertionError("answer test must not execute a semantic query");
                },
                verifier,
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

    private AgentLoopRequest request() {
        return request(new SessionId("session-1"), "What is verified?");
    }

    private AgentLoopRequest request(SessionId sessionId, String question) {
        return new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                sessionId,
                question,
                new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0, 1, 0));
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
