package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
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
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerminalResponseCoordinator terminal acceptance 取消仲裁的持久化行為測試
 */
class TerminalResponseCoordinatorTest {

    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final AnalysisAttemptId ATTEMPT_ID = new AnalysisAttemptId("attempt-1");
    private static final SessionId SESSION_ID = new SessionId("session-1");
    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");
    private static final RepositoryId REPOSITORY_ID = new RepositoryId("repo-1");
    private static final CapabilityPolicy CAPABILITY = new CapabilityPolicy(
            "trace", "v1", Set.of(CandidateKind.REPOSITORY), 1, 1);

    @Test
    void concludesCancelledWithoutAppendingATurnWhenClarificationAcceptanceIsCancelled() {
        TerminalCancellationTransitionPort transitionPort = new TerminalCancellationTransitionPort();
        AgentRunTransitions transitions = new AgentRunTransitions(
                new AgentTransitionCommitter(new AgentStateReducer(), transitionPort));
        AgentRunState state = runningState(transitions);
        FakeSessionAdapter sessionPort = new FakeSessionAdapter();
        TerminalResponseCoordinator coordinator = new TerminalResponseCoordinator(transitions, sessionPort);
        ClarifyAction clarification = new ClarifyAction(
                "Which repository should be inspected?",
                List.of(new CandidateHandleRef(state.currentAttempt().issuedCandidates().keySet().iterator().next().value())),
                "Need repository scope");

        AgentLoopResult result = coordinator.acceptClarification(request(), state, clarification);

        assertThat(result.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(transitionPort.state(RUN_ID).finalOutcome()).contains(RunOutcome.CANCELLED);
        assertThat(transitionPort.events())
                .contains(AgentEvent.RunConcluded.class)
                .doesNotContain(AgentEvent.ClarificationAccepted.class);
        assertThat(sessionPort.read(SESSION_ID).turns()).isEmpty();
    }

    private AgentRunState runningState(AgentRunTransitions transitions) {
        AgentRunState initial = AgentRunState.initial(
                RUN_ID,
                ATTEMPT_ID,
                new AttemptBudget(3, 0, 2, 0, 1, 0, 2, 0, 1, 0),
                new RunRequestIdentity("session-1", PARTICIPANT, "What does this repository flow do?"));
        RunAttempt context = new ContextIssuer().issueInitial(
                RUN_ID,
                ATTEMPT_ID,
                RevisionVector.empty(),
                List.of(CAPABILITY),
                List.of(new RepositoryDescriptor(REPOSITORY_ID, "Repository one")));
        return transitions.bootstrap(initial, context);
    }

    private AgentLoopRequest request() {
        return new AgentLoopRequest(
                RUN_ID,
                SESSION_ID,
                PARTICIPANT,
                "What does this repository flow do?",
                new AttemptBudget(3, 0, 2, 0, 1, 0, 2, 0, 1, 0));
    }

    private static final class TerminalCancellationTransitionPort implements AgentTransitionPort {

        private final List<Class<?>> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

        @Override
        public AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState state = bootstrap.finalTransition().candidateState();
            states.put(state.runId(), state);
            events.add(bootstrap.runStarted().event().getClass());
            events.add(bootstrap.attemptStarted().event().getClass());
            events.add(bootstrap.contextIssued().event().getClass());
            return state;
        }

        @Override
        public AgentRunState commit(AgentTransition transition) {
            AgentRunState state = transition.candidateState();
            states.put(state.runId(), state);
            events.add(transition.event().getClass());
            return state;
        }

        @Override
        public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            throw new TerminalAcceptanceCancelledException("durable cancellation won terminal arbitration");
        }

        @Override
        public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private List<Class<?>> events() {
            return List.copyOf(events);
        }

        private AgentRunState state(AnalysisRunId runId) {
            return findByRunId(runId).orElseThrow();
        }
    }
}
