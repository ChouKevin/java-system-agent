package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisWarning;
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RepositorySelection;
import com.java.system.agent.analysis.domain.RevisionVector;
import com.java.system.agent.analysis.port.out.AnalysisTransitionPort;
import com.java.system.agent.runtime.adapter.fake.InMemoryAnalysisTransitionAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void acceptsObjectTransitionPortAndReturnsItsCandidate() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AnalysisState candidateState = currentState.withStateRevision(1);
        StateReducer reducer = (state, reducedEvent) -> new StateTransition(reducedEvent, candidateState);
        AnalysisTransitionPort<Object> transitionPort = transition ->
                ((StateTransition) transition).candidateState();
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        AnalysisState committedState = committer.apply(currentState, event);

        assertThat(committedState).isSameAs(candidateState);
    }

    @Test
    void rejectsNullTransitionReturnedByReducerBeforeInvokingPort() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AtomicBoolean portInvoked = new AtomicBoolean();
        StateReducer reducer = (state, reducedEvent) -> null;
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> {
            portInvoked.set(true);
            return currentState.withStateRevision(1);
        };
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        assertThatNullPointerException()
                .isThrownBy(() -> committer.apply(currentState, event))
                .withMessage("state reducer must return a transition");
        assertThat(portInvoked).isFalse();
    }

    @Test
    void acceptsEqualButDistinctCommittedCandidateState() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        RepositoryScope scope = RepositoryScope.of(List.of(new RepositorySelection(
                new RepositoryId("order-service"),
                "Selected for committed state rehydration",
                true,
                RepositoryDiscoverySource.USER)));
        RevisionVector candidateRevisionVector = RevisionVector.empty().pin(
                scope, new RepositoryId("order-service"), new RepositoryRevision("ord-456"));
        AnalysisState candidateState = new AnalysisState(
                currentState.runId(),
                currentState.attemptId(),
                1,
                currentState.status(),
                scope,
                candidateRevisionVector,
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                currentState.budget());
        RevisionVector rehydratedRevisionVector = RevisionVector.empty().pin(
                scope, new RepositoryId("order-service"), new RepositoryRevision("ord-456"));
        AnalysisState distinctCommittedState = new AnalysisState(
                candidateState.runId(),
                candidateState.attemptId(),
                candidateState.stateRevision(),
                candidateState.status(),
                candidateState.repositoryScope(),
                rehydratedRevisionVector,
                candidateState.pendingNeeds(),
                candidateState.resolvedNeedIds(),
                candidateState.evidenceBindings(),
                candidateState.warnings(),
                candidateState.budget());
        StateReducer reducer = (state, reducedEvent) -> new StateTransition(reducedEvent, candidateState);
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> distinctCommittedState;
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        AnalysisState committedState = committer.apply(currentState, event);

        assertThat(committedState).isEqualTo(candidateState);
        assertThat(committedState).isNotSameAs(candidateState);
        assertThat(committedState).isSameAs(distinctCommittedState);
        assertThat(committedState.revisionVector()).isNotSameAs(candidateState.revisionVector());
    }

    @Test
    void reducesBeforeCommittingExactTransitionProducedByReducer() {
        AnalysisState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AnalysisState candidateState = currentState.withStateRevision(1);
        StateTransition expectedTransition = new StateTransition(event, candidateState);
        List<String> callOrder = new ArrayList<>();
        StateReducer reducer = (state, reducedEvent) -> {
            callOrder.add("reduce");
            return expectedTransition;
        };
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> {
            callOrder.add("commit");
            assertThat(transition).isSameAs(expectedTransition);
            return candidateState;
        };
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        AnalysisState committedState = committer.apply(currentState, event);

        assertThat(committedState).isSameAs(candidateState);
        assertThat(callOrder).containsExactly("reduce", "commit");
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
