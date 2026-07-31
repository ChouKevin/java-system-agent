package com.java.system.agent.answering.application;

import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
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
 * QueryActionExecutor capability 執行整合契約失敗的持久化行為測試
 */
class QueryActionExecutorTest {

    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final AnalysisAttemptId ATTEMPT_ID = new AnalysisAttemptId("attempt-1");
    private static final RepositoryId REPOSITORY_ID = new RepositoryId("repo-1");
    private static final RepositoryRevision REPOSITORY_REVISION = new RepositoryRevision("rev-1");
    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");
    private static final CapabilityPolicy CAPABILITY = new CapabilityPolicy(
            "trace", "v1", Set.of(CandidateKind.REPOSITORY), 1, 1);

    @Test
    void concludesDurablyBeforeSurfacingACapabilityContractFailure() {
        RecordingTransitionPort transitionPort = new RecordingTransitionPort();
        AgentRunTransitions transitions = new AgentRunTransitions(
                new AgentTransitionCommitter(new AgentStateReducer(), transitionPort));
        ContextIssuer contextIssuer = new ContextIssuer();
        AgentRunState state = runningState(transitions, contextIssuer);
        AtomicInteger capabilityCalls = new AtomicInteger();
        CapabilityExecutionContractException capabilityFailure =
                new CapabilityExecutionContractException("planning registry contract failed");
        QueryActionExecutor executor = new QueryActionExecutor(
                new AgentLoopTelemetry(
                        invocation -> {
                            capabilityCalls.incrementAndGet();
                            throw capabilityFailure;
                        },
                        (mode, context) -> new AnswerVerificationResult.ContractAccepted(),
                        () -> List.of(CAPABILITY),
                        () -> List.of(repositoryDescriptor()),
                        repositoryId -> RepositoryRevisionResult.ready(REPOSITORY_REVISION)),
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator(),
                contextIssuer,
                transitions,
                new TerminalResponseCoordinator(transitions, new FakeSessionAdapter()));
        QueryAction action = query(state);

        assertThatThrownBy(() -> executor.execute(
                request(),
                state,
                action,
                List.copyOf(state.currentAttempt().issuedCandidates().values()),
                1,
                List.of(CAPABILITY),
                List.of(repositoryDescriptor()),
                Set.of(REPOSITORY_ID)))
                .isInstanceOf(AnswerExecutionContractException.class)
                .satisfies(throwable -> {
                    AnswerExecutionContractException exception = (AnswerExecutionContractException) throwable;
                    assertThat(exception.failure()).isEqualTo(AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT);
                    assertThat(exception).hasCause(capabilityFailure);
                });

        AgentRunState persisted = transitionPort.state(RUN_ID);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(persisted.finalOutcome()).contains(RunOutcome.FAILED);
        assertThat(persisted.failureReason()).contains(RunFailureReason.PLANNING_TOOL_CONTRACT);
        assertThat(eventTypes(transitionPort.events()))
                .containsSubsequence(
                        AgentEvent.ActionAccepted.class,
                        AgentEvent.QueryBudgetConsumed.class,
                        AgentEvent.RunConcluded.class);
    }

    private AgentRunState runningState(AgentRunTransitions transitions, ContextIssuer contextIssuer) {
        AgentRunState initial = AgentRunState.initial(
                RUN_ID,
                ATTEMPT_ID,
                new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 0),
                new RunRequestIdentity("session-1", PARTICIPANT, "What does this repository flow do?"));
        RunAttempt context = contextIssuer.issueInitial(
                RUN_ID,
                ATTEMPT_ID,
                RevisionVector.empty().pin(REPOSITORY_ID, REPOSITORY_REVISION),
                List.of(CAPABILITY),
                List.of(repositoryDescriptor()));
        return transitions.bootstrap(initial, context);
    }

    private QueryAction query(AgentRunState state) {
        return new QueryAction(
                state.currentAttempt().issuedCapabilities().keySet().iterator().next(),
                List.of(new CandidateHandleRef(state.currentAttempt().issuedCandidates().keySet().iterator().next().value())),
                "Trace the repository flow",
                new CapabilityInputPayload("trace"),
                "Need repository evidence");
    }

    private AgentLoopRequest request() {
        return new AgentLoopRequest(
                RUN_ID,
                new SessionId("session-1"),
                PARTICIPANT,
                "What does this repository flow do?",
                new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 0));
    }

    private RepositoryDescriptor repositoryDescriptor() {
        return new RepositoryDescriptor(REPOSITORY_ID, "Repository one");
    }

    private List<Class<?>> eventTypes(List<AgentEvent> events) {
        List<Class<?>> eventTypes = new ArrayList<>();
        for (AgentEvent event : events) {
            eventTypes.add(event.getClass());
        }
        return List.copyOf(eventTypes);
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {

        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

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
            AgentRunState state = transition.candidateState();
            states.put(state.runId(), state);
            events.add(transition.event());
            return state;
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

        private AgentRunState state(AnalysisRunId runId) {
            return findByRunId(runId).orElseThrow();
        }
    }
}
