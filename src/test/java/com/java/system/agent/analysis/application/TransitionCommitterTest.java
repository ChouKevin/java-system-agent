package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisWarning;
import com.java.system.agent.analysis.port.out.AnalysisTransitionPort;
import com.java.system.agent.runtime.adapter.fake.InMemoryAnalysisTransitionAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionCommitterTest {

    @Test
    void commitsEventBeforeReturningReducerCandidateState() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AnalysisState candidateState = currentState.withStateRevision(1);
        StateReducer reducer = (state, reducedEvent) -> new StateTransition(reducedEvent, candidateState);
        InMemoryAnalysisTransitionAdapter transitionPort = new InMemoryAnalysisTransitionAdapter();
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        AnalysisState committedState = committer.apply(currentState, event);

        assertThat(committedState).isSameAs(candidateState);
        assertThat(committedState.stateRevision()).isEqualTo(1);
        assertThat(transitionPort.events()).containsExactly(event);
        assertThat(transitionPort.commitCount()).isEqualTo(1);
    }

    @Test
    void wrapsFailedCommitWithoutAdoptingReducerCandidateOrRecordingEvent() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        InMemoryAnalysisTransitionAdapter transitionPort = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(1);
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), transitionPort);

        assertThatThrownBy(() -> committer.apply(currentState, event))
                .isInstanceOf(AnalysisTransitionCommitException.class)
                .hasMessage("analysis transition could not be committed")
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("configured fake transition failure at commit 1");
        assertThat(currentState.stateRevision()).isZero();
        assertThat(transitionPort.events()).isEmpty();
        assertThat(transitionPort.commitCount()).isEqualTo(1);
    }

    @Test
    void propagatesExistingTransitionCommitExceptionWithoutWrapping() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AnalysisTransitionCommitException commitException =
                new AnalysisTransitionCommitException("pre-created transition failure");
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> {
            throw commitException;
        };
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), transitionPort);

        assertThatThrownBy(() -> committer.apply(currentState, event))
                .isSameAs(commitException);
    }

    @Test
    void propagatesExistingErrorWithoutWrapping() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AssertionError error = new AssertionError("pre-created transition error");
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> {
            throw error;
        };
        TransitionCommitter committer = new TransitionCommitter(
                new DefaultStateReducer(), transitionPort);

        assertThatThrownBy(() -> committer.apply(currentState, event))
                .isSameAs(error);
    }

    @Test
    void rejectsCommittedStateThatDiffersFromReducerCandidate() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> currentState;
        TransitionCommitter committer = new TransitionCommitter(
                new DefaultStateReducer(), transitionPort);

        assertThatThrownBy(() -> committer.apply(currentState, event))
                .isInstanceOf(AnalysisTransitionCommitException.class)
                .hasMessageContaining("different candidate state");
        assertThat(currentState.stateRevision()).isZero();
    }

    @Test
    void rejectsNullCommittedStateAsTransitionCommitFailure() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> null;
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), transitionPort);

        assertThatThrownBy(() -> committer.apply(currentState, event))
                .isInstanceOf(AnalysisTransitionCommitException.class)
                .hasMessageContaining("returned no candidate state");
    }

    @Test
    void rejectsNullDependenciesAndApplyInputs() {
        InMemoryAnalysisTransitionAdapter transitionPort = new InMemoryAnalysisTransitionAdapter();
        StateReducer reducer = new DefaultStateReducer();
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);

        assertThatNullPointerException()
                .isThrownBy(() -> new TransitionCommitter(null, transitionPort));
        assertThatNullPointerException()
                .isThrownBy(() -> new TransitionCommitter(reducer, null));
        assertThatNullPointerException().isThrownBy(() -> committer.apply(null, event));
        assertThatNullPointerException().isThrownBy(() -> committer.apply(currentState, null));
    }

    private AnalysisState initialState() {
        return AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(10, 5));
    }

    private AnalysisEvent warningEvent(AnalysisState currentState) {
        return new AnalysisEvent.WarningRecorded(
                currentState.runId(),
                currentState.attemptId(),
                currentState.stateRevision(),
                new AnalysisWarning("TEST", "test transition"));
    }
}
