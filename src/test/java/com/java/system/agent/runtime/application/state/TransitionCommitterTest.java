package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.AnalysisTransitionPort;
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
        AttemptState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AttemptState candidateState = currentState.withStateRevision(1);
        StateReducer reducer = (state, reducedEvent) -> new StateTransition(reducedEvent, candidateState);
        InMemoryAnalysisTransitionAdapter transitionPort = new InMemoryAnalysisTransitionAdapter();
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        AttemptState committedState = committer.apply(currentState, event);

        assertThat(committedState).isSameAs(candidateState);
        assertThat(committedState.stateRevision()).isEqualTo(1);
        assertThat(transitionPort.events()).containsExactly(event);
        assertThat(transitionPort.commitCount()).isEqualTo(1);
    }

    @Test
    void acceptsObjectTransitionPortAndReturnsItsCandidate() {
        AttemptState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AttemptState candidateState = currentState.withStateRevision(1);
        StateReducer reducer = (state, reducedEvent) -> new StateTransition(reducedEvent, candidateState);
        AnalysisTransitionPort<Object> transitionPort = transition ->
                ((StateTransition) transition).candidateState();
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        AttemptState committedState = committer.apply(currentState, event);

        assertThat(committedState).isSameAs(candidateState);
    }

    @Test
    void rejectsNullTransitionReturnedByReducerBeforeInvokingPort() {
        AttemptState currentState = initialState();
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
        AttemptState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        RepositoryScope scope = RepositoryScope.of(List.of(new RepositorySelection(
                new RepositoryId("order-service"),
                "Selected for committed state rehydration",
                true,
                RepositoryDiscoverySource.USER)));
        RevisionVector candidateRevisionVector = RevisionVector.empty().pin(
                scope, new RepositoryId("order-service"), new RepositoryRevision("ord-456"));
        AttemptState candidateState = new AttemptState(
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
        RepositoryScope rehydratedScope = RepositoryScope.of(List.of(new RepositorySelection(
                new RepositoryId("order-service"),
                "Selected for committed state rehydration",
                true,
                RepositoryDiscoverySource.USER)));
        RevisionVector rehydratedRevisionVector = RevisionVector.empty().pin(
                rehydratedScope, new RepositoryId("order-service"), new RepositoryRevision("ord-456"));
        AttemptState distinctCommittedState = new AttemptState(
                candidateState.runId(),
                candidateState.attemptId(),
                candidateState.stateRevision(),
                candidateState.status(),
                rehydratedScope,
                rehydratedRevisionVector,
                candidateState.pendingNeeds(),
                candidateState.resolvedNeedIds(),
                candidateState.evidenceBindings(),
                candidateState.warnings(),
                candidateState.budget());
        StateReducer reducer = (state, reducedEvent) -> new StateTransition(reducedEvent, candidateState);
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> distinctCommittedState;
        TransitionCommitter committer = new TransitionCommitter(reducer, transitionPort);

        AttemptState committedState = committer.apply(currentState, event);

        assertThat(committedState).isEqualTo(candidateState);
        assertThat(committedState).isNotSameAs(candidateState);
        assertThat(committedState).isSameAs(distinctCommittedState);
        assertThat(committedState.repositoryScope()).isNotSameAs(candidateState.repositoryScope());
        assertThat(committedState.revisionVector()).isNotSameAs(candidateState.revisionVector());
    }

    @Test
    void reducesBeforeCommittingExactTransitionProducedByReducer() {
        AttemptState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);
        AttemptState candidateState = currentState.withStateRevision(1);
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

        AttemptState committedState = committer.apply(currentState, event);

        assertThat(committedState).isSameAs(candidateState);
        assertThat(callOrder).containsExactly("reduce", "commit");
    }

    @Test
    void wrapsFailedCommitWithoutAdoptingReducerCandidateOrRecordingEvent() {
        AttemptState currentState = initialState();
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
        AttemptState currentState = initialState();
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
        AttemptState currentState = initialState();
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
        AttemptState currentState = initialState();
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
        AttemptState currentState = initialState();
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
        AttemptState currentState = initialState();
        AnalysisEvent event = warningEvent(currentState);

        assertThatNullPointerException()
                .isThrownBy(() -> new TransitionCommitter(null, transitionPort));
        assertThatNullPointerException()
                .isThrownBy(() -> new TransitionCommitter(reducer, null));
        assertThatNullPointerException().isThrownBy(() -> committer.apply(null, event));
        assertThatNullPointerException().isThrownBy(() -> committer.apply(currentState, null));
    }

    private AttemptState initialState() {
        return AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(10, 5));
    }

    private AnalysisEvent warningEvent(AttemptState currentState) {
        return new AnalysisEvent.WarningRecorded(
                currentState.runId(),
                currentState.attemptId(),
                currentState.stateRevision(),
                new AnalysisWarning("TEST", "test transition"));
    }
}
