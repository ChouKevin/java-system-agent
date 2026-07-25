package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.application.state.AnalysisEvent;
import com.java.system.agent.runtime.application.state.StateTransition;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.port.out.AnalysisTransitionPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class InMemoryAnalysisTransitionAdapter implements AnalysisTransitionPort<StateTransition> {

    private final List<AnalysisEvent> committedEvents = new ArrayList<>();
    private Optional<Long> failingCommitNumber = Optional.empty();
    private long commitCount;

    public synchronized InMemoryAnalysisTransitionAdapter failAtCommit(long commitNumber) {
        if (commitNumber < 1) {
            throw new IllegalArgumentException("failing commit number must be positive");
        }
        failingCommitNumber = Optional.of(commitNumber);
        return this;
    }

    @Override
    public synchronized AttemptState commit(StateTransition transition) {
        Objects.requireNonNull(transition, "state transition must not be null");
        commitCount++;
        if (failingCommitNumber.filter(number -> number == commitCount).isPresent()) {
            throw new IllegalStateException("configured fake transition failure at commit " + commitCount);
        }
        committedEvents.add(transition.event());
        return transition.candidateState();
    }

    public synchronized long commitCount() {
        return commitCount;
    }

    public synchronized List<AnalysisEvent> events() {
        return List.copyOf(committedEvents);
    }
}
