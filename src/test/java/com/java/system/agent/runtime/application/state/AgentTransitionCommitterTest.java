package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentBootstrap;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentTransition;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunRequestIdentity;
import com.java.system.agent.runtime.port.out.AgentTransitionPort;
import com.java.system.agent.runtime.port.out.AgentTransitionConflictException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTransitionCommitterTest {

    @Test
    void should_adopt_only_the_final_state_of_an_atomic_bootstrap() {
        AgentRunState currentState = initialState();
        RecordingTransitionPort port = new RecordingTransitionPort();
        AgentTransitionCommitter committer = new AgentTransitionCommitter(new AgentStateReducer(), port);

        AgentRunState committed = committer.bootstrap(currentState, currentState.currentAttempt());

        assertThat(committed.stateRevision()).isEqualTo(3);
        assertThat(port.committedBootstrap).isNotNull();
        assertThat(port.committedBootstrap.runStarted().event()).isInstanceOf(AgentEvent.RunStarted.class);
        assertThat(port.committedBootstrap.attemptStarted().event()).isInstanceOf(AgentEvent.AttemptStarted.class);
        assertThat(port.committedBootstrap.contextIssued().event()).isInstanceOf(AgentEvent.ContextIssued.class);
        assertThat(committed).isSameAs(port.committedBootstrap.finalTransition().candidateState());
    }

    @Test
    void should_fail_without_adopting_mismatched_or_failed_persistence() {
        AgentRunState currentState = initialState();
        AgentTransitionPort mismatchedPort = new AgentTransitionPort() {
            @Override
            public AgentRunState bootstrap(AgentBootstrap bootstrap) {
                return currentState;
            }

            @Override
            public AgentRunState commit(AgentTransition transition) {
                return currentState;
            }

            @Override
            public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
                return currentState;
            }

            @Override
            public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
                return Optional.empty();
            }
        };
        AgentTransitionCommitter mismatched = new AgentTransitionCommitter(new AgentStateReducer(), mismatchedPort);
        AgentTransitionPort failingPort = new AgentTransitionPort() {
            @Override
            public AgentRunState bootstrap(AgentBootstrap bootstrap) {
                throw new IllegalStateException("persistence unavailable");
            }

            @Override
            public AgentRunState commit(AgentTransition transition) {
                throw new IllegalStateException("persistence unavailable");
            }

            @Override
            public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
                throw new IllegalStateException("persistence unavailable");
            }

            @Override
            public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
                return Optional.empty();
            }
        };
        AgentTransitionCommitter failing = new AgentTransitionCommitter(new AgentStateReducer(), failingPort);
        assertThatThrownBy(() -> mismatched.bootstrap(currentState, currentState.currentAttempt()))
                .isInstanceOf(AgentTransitionCommitException.class);
        assertThatThrownBy(() -> failing.bootstrap(currentState, currentState.currentAttempt()))
                .isInstanceOf(AgentTransitionCommitException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(currentState.stateRevision()).isZero();
    }

    private AgentRunState initialState() {
        return AgentRunState.initial(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                new AttemptBudget(2, 0, 2, 0, 2, 0, 2, 0, 1, 0),
                new RunRequestIdentity("session-1", "question"));
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {

        private AgentBootstrap committedBootstrap;
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState existing = states.get(bootstrap.finalTransition().candidateState().runId());
            if (Objects.nonNull(existing)) {
                throw new AgentTransitionConflictException("run already exists");
            }
            committedBootstrap = bootstrap;
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
    }
}
