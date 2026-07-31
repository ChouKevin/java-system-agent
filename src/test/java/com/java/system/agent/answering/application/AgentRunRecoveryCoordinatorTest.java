package com.java.system.agent.answering.application;

import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
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
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
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
 * AgentRunRecoveryCoordinator 的 durable state dispatch 邊界測試
 */
class AgentRunRecoveryCoordinatorTest {

    @Test
    void initialWithoutPersistedStateBootstrapsAnActiveExecution() {
        Fixture fixture = fixture((mode, context) -> accepted());

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.initialRequest());

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Active.class);
        assertThat(fixture.port().events()).filteredOn(AgentEvent.RunStarted.class::isInstance).hasSize(1);
    }

    @Test
    void capacityResumeReturnsPersistedRunningExecutionWithoutReloadingCatalogs() {
        Fixture fixture = fixture((mode, context) -> accepted());
        fixture.coordinator().recover(fixture.initialRequest());
        int catalogReads = fixture.catalogReads().get();

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.request(AnswerExecutionMode.CAPACITY_RESUME, 1));

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Active.class);
        assertThat(fixture.catalogReads()).hasValue(catalogReads);
    }

    @Test
    void pendingVerificationCallsOnlyTheVerifierAndDoesNotRestartTheAttempt() {
        AtomicInteger verifications = new AtomicInteger();
        Fixture fixture = fixture((mode, context) -> {
            if (verifications.incrementAndGet() == 1) {
                throw new AnswerVerificationUnavailableException("temporary outage", null); // cs-allow
            }
            return accepted();
        });
        AgentRunRecoveryOutcome.Active active = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.initialRequest());
        assertThatThrownBy(() -> fixture.answerExecutor().execute(
                fixture.initialRequest(), fixture.session().read(fixture.initialRequest().sessionId()), active.execution().state(), answer(), 1))
                .isInstanceOf(AnswerExecutionUnavailableException.class);

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.request(AnswerExecutionMode.RETRY, 2));

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Terminal.class);
        assertThat(verifications).hasValue(2);
        assertThat(fixture.port().events()).filteredOn(AgentEvent.AttemptInvalidated.class::isInstance).isEmpty();
    }

    @Test
    void terminalReconciliationDoesNotCallExternalExecutionPorts() {
        Fixture fixture = fixture((mode, context) -> {
            throw new AssertionError("terminal reconciliation must not verify");
        });
        fixture.coordinator().recover(fixture.initialRequest());
        int catalogReads = fixture.catalogReads().get();

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(
                fixture.request(AnswerExecutionMode.TERMINAL_RECONCILIATION, 4));

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Terminal.class);
        assertThat(fixture.catalogReads()).hasValue(catalogReads);
    }

    private static Fixture fixture(com.java.system.agent.answering.port.out.AnswerVerificationPort verifier) {
        RecordingTransitionPort port = new RecordingTransitionPort();
        FakeSessionAdapter session = new FakeSessionAdapter();
        AtomicInteger catalogReads = new AtomicInteger();
        AgentRunTransitions transitions = new AgentRunTransitions(new AgentTransitionCommitter(new AgentStateReducer(), port));
        CapabilityCatalogPort capabilities = () -> {
            catalogReads.incrementAndGet();
            return List.of();
        };
        RepositoryCatalogPort repositories = () -> {
            catalogReads.incrementAndGet();
            return List.of();
        };
        CapabilityExecutionPort execution = invocation -> {
            throw new AssertionError("recovery test must not execute capabilities");
        };
        RepositoryRevisionPort revisions = repositoryId -> {
            throw new AssertionError("recovery test must not resolve revisions");
        };
        AgentLoopTelemetry telemetry = new AgentLoopTelemetry(execution, verifier, capabilities, repositories, revisions);
        TerminalResponseCoordinator terminal = new TerminalResponseCoordinator(transitions, session);
        AnswerActionExecutor answerExecutor = new AnswerActionExecutor(
                telemetry, new FakeCancellationAdapter(), AnswerVerificationMode.LLM,
                new AnswerDocumentValidator(), new AnswerVerdictValidator(), transitions, terminal);
        AgentRunRecoveryCoordinator coordinator = new AgentRunRecoveryCoordinator(
                transitions,
                telemetry,
                new ContextIssuer(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1"), new AnalysisAttemptId("attempt-2")),
                session,
                answerExecutor,
                terminal);
        return new Fixture(coordinator, answerExecutor, session, port, catalogReads);
    }

    private static AnswerVerificationResult accepted() {
        return new AnswerVerificationResult.LlmVerdict(new com.java.system.agent.answering.domain.answer.AnswerVerdict(
                AnswerDisposition.ACCEPTED_COMPLETE, List.of(), List.of(), List.of(), List.of()));
    }

    private AgentLoopRequest initialRequest() {
        return request(AnswerExecutionMode.INITIAL, 1);
    }

    private AgentLoopRequest request(AnswerExecutionMode mode, int attempt) {
        return new AgentLoopRequest(
                new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                "What is verified?", new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0), mode, attempt);
    }

    private static AnswerAction answer() {
        return new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "Verified answer", Optional.empty(), Set.of(), Set.of()))));
    }

    private record Fixture(
            AgentRunRecoveryCoordinator coordinator,
            AnswerActionExecutor answerExecutor,
            FakeSessionAdapter session,
            RecordingTransitionPort port,
            AtomicInteger catalogReads) {

        private AgentLoopRequest initialRequest() {
            return new AgentLoopRequest(
                    new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                    "What is verified?", new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0));
        }

        private AgentLoopRequest request(AnswerExecutionMode mode, int attempt) {
            return new AgentLoopRequest(
                    new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                    "What is verified?", new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0), mode, attempt);
        }
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
