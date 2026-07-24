package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.port.out.AnalysisTransitionPort;

import java.util.Objects;

public final class TransitionCommitter {

    private final StateReducer reducer;
    private final AnalysisTransitionPort<? super StateTransition> transitionPort;

    public TransitionCommitter(
            StateReducer reducer,
            AnalysisTransitionPort<? super StateTransition> transitionPort) {
        this.reducer = Objects.requireNonNull(reducer, "state reducer must not be null");
        this.transitionPort = Objects.requireNonNull(
                transitionPort, "analysis transition port must not be null");
    }

    public AnalysisState apply(AnalysisState currentState, AnalysisEvent event) {
        Objects.requireNonNull(currentState, "current analysis state must not be null");
        Objects.requireNonNull(event, "analysis event must not be null");
        StateTransition transition = Objects.requireNonNull(
                reducer.reduce(currentState, event), "state reducer must return a transition");
        try {
            AnalysisState committedState = transitionPort.commit(transition);
            if (Objects.isNull(committedState)) {
                throw new AnalysisTransitionCommitException(
                        "analysis transition commit returned no candidate state");
            }
            if (!transition.candidateState().equals(committedState)) {
                throw new AnalysisTransitionCommitException(
                        "analysis transition commit returned a different candidate state");
            }
            return committedState;
        } catch (AnalysisTransitionCommitException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AnalysisTransitionCommitException(
                    "analysis transition could not be committed", exception);
        }
    }
}
