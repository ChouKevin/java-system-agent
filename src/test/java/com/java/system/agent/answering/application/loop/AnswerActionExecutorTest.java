package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.HttpMutationResult;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AnswerActionExecutor 的驗證與 terminal acceptance 邊界測試
 */
class AnswerActionExecutorTest {

    @Test
    void acceptsAFreshVerifiedAnswerAndAppendsTheSessionTurn() {
        Fixture fixture = fixture((mode, context) -> new AnswerVerificationResult.LlmVerdict(accepted()));

        ActionLaneOutcome outcome = fixture.executor().execute(
                fixture.request(), fixture.session().read(fixture.request().sessionId()), fixture.state(), answer(), 1);

        assertThat(outcome).isInstanceOf(ActionLaneOutcome.Terminal.class);
        assertThat(((ActionLaneOutcome.Terminal) outcome).result().responseText()).isEqualTo("Verified answer");
        assertThat(fixture.session().read(fixture.request().sessionId()).turns()).hasSize(1);
    }

    @Test
    void resumesPendingAnswerByCallingOnlyTheVerifier() {
        AtomicInteger verifications = new AtomicInteger();
        Fixture fixture = fixture((mode, context) -> {
            if (verifications.incrementAndGet() == 1) {
                throw new AnswerVerificationUnavailableException("temporary outage", null); // cs-allow
            }
            return new AnswerVerificationResult.LlmVerdict(accepted());
        });

        assertThatThrownBy(() -> fixture.executor().execute(
                fixture.request(), fixture.session().read(fixture.request().sessionId()), fixture.state(), answer(), 1))
                .isInstanceOf(AnswerExecutionUnavailableException.class);
        AgentRunState pending = fixture.transitions().findByRunId(fixture.request().runId()).orElseThrow();

        ActionLaneOutcome outcome = fixture.executor().resumePending(
                fixture.request(), fixture.session().read(fixture.request().sessionId()), pending);

        assertThat(outcome).isInstanceOf(ActionLaneOutcome.Terminal.class);
        assertThat(verifications).hasValue(2);
    }

    @Test
    void recordsRejectionObservationsAndContinuesAfterARejectedVerdict() {
        Fixture fixture = fixture((mode, context) -> new AnswerVerificationResult.LlmVerdict(
                new AnswerVerdict(
                        AnswerDisposition.REJECTED, List.of(), List.of(), List.of(), List.of("rewrite"))));

        ActionLaneOutcome outcome = fixture.executor().execute(
                fixture.request(), fixture.session().read(fixture.request().sessionId()), fixture.state(), answer(), 1);

        assertThat(outcome).isInstanceOf(ActionLaneOutcome.Continue.class);
        assertThat(((ActionLaneOutcome.Continue) outcome).latestRejection()).contains("rejection reason: rewrite");
        assertThat(fixture.transitionPort().events()).anyMatch(AgentEvent.AnswerRejected.class::isInstance);
        assertThat(fixture.transitionPort().events()).anyMatch(AgentEvent.ObservationRecorded.class::isInstance);
    }

    @Test
    void cancellationBeforeAcceptanceDoesNotAppendAnAnswerTurn() {
        Fixture fixture = fixture(runId -> true, (mode, context) -> {
            throw new AssertionError("cancelled answer must not be verified");
        });

        ActionLaneOutcome outcome = fixture.executor().execute(
                fixture.request(), fixture.session().read(fixture.request().sessionId()), fixture.state(), answer(), 1);

        assertThat(outcome).isInstanceOf(ActionLaneOutcome.Terminal.class);
        assertThat(fixture.session().read(fixture.request().sessionId()).turns()).isEmpty();
    }

    private static Fixture fixture(AnswerVerificationPort verificationPort) {
        return fixture(new FakeCancellationAdapter(), verificationPort);
    }

    private static Fixture fixture(
            AnalysisCancellationPort cancellation,
            AnswerVerificationPort verificationPort) {
        RecordingTransitionPort port = new RecordingTransitionPort();
        AgentRunTransitions transitions = new AgentRunTransitions(new AgentTransitionCommitter(new AgentStateReducer(), port));
        FakeSessionAdapter session = new FakeSessionAdapter();
        AgentLoopTelemetry telemetry = new AgentLoopTelemetry(
                failingCapabilities(), action -> new HttpMutationResult.NotImplemented(),
                verificationPort, emptyCapabilities(), emptyRepositories(), failingRevisions());
        TerminalResponseCoordinator terminal = new TerminalResponseCoordinator(transitions, session);
        AnswerActionExecutor executor = new AnswerActionExecutor(
                telemetry, cancellation, AnswerVerificationMode.LLM, new AnswerDocumentValidator(),
                new AnswerVerdictValidator(), transitions, terminal);
        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                "What is verified?", new AttemptBudget(2, 0, 1, 0, 1, 0, 2, 0, 1, 0));
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AgentRunState initial = AgentRunState.initial(
                request.runId(), attemptId, 1, request.budget(),
                new RunRequestIdentity(
                        request.sessionId().value(), request.participant(), request.question()));
        AgentRunState state = transitions.bootstrap(initial, RunAttempt.empty(attemptId));
        return new Fixture(executor, transitions, session, request, state, port);
    }

    private static AnswerAction answer() {
        return new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "Verified answer", Optional.empty(), Set.of(), Set.of()))));
    }

    private static AnswerVerdict accepted() {
        return new AnswerVerdict(
                AnswerDisposition.ACCEPTED_COMPLETE, List.of(), List.of(), List.of(), List.of());
    }

    private static CapabilityExecutionPort failingCapabilities() {
        return invocation -> { throw new AssertionError("answer test must not execute capabilities"); };
    }

    private static CapabilityCatalogPort emptyCapabilities() {
        return List::of;
    }

    private static RepositoryCatalogPort emptyRepositories() {
        return List::of;
    }

    private static RepositoryRevisionPort failingRevisions() {
        return repositoryId -> { throw new AssertionError("answer test must not resolve revisions"); };
    }

    private record Fixture(
            AnswerActionExecutor executor,
            AgentRunTransitions transitions,
            FakeSessionAdapter session,
            AgentLoopRequest request,
            AgentRunState state,
            RecordingTransitionPort transitionPort) {
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState state = bootstrap.finalTransition().candidateState();
            states.put(state.runId(), state);
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            return state;
        }

        @Override
        public AgentRunState commit(AgentTransition transition) {
            states.put(transition.candidateState().runId(), transition.candidateState());
            events.add(transition.event());
            return transition.candidateState();
        }

        @Override
        public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return commit(transition);
        }

        @Override
        public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private List<AgentEvent> events() {
            return List.copyOf(events);
        }
    }
}
