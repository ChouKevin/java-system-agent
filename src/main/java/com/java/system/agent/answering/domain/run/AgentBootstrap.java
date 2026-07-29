package com.java.system.agent.answering.domain.run;

import java.util.Objects;

/**
 * 單一 Agent Run 首次建立時必須以同一個原子邊界提交的 transition 批次
 */
public record AgentBootstrap(
        AgentTransition runStarted,
        AgentTransition attemptStarted,
        AgentTransition contextIssued) {

    public AgentBootstrap {
        Objects.requireNonNull(runStarted, "bootstrap run started transition must not be null");
        Objects.requireNonNull(attemptStarted, "bootstrap attempt started transition must not be null");
        Objects.requireNonNull(contextIssued, "bootstrap context issued transition must not be null");
        if (!(runStarted.event() instanceof AgentEvent.RunStarted)
                || !(attemptStarted.event() instanceof AgentEvent.AttemptStarted)
                || !(contextIssued.event() instanceof AgentEvent.ContextIssued)) {
            throw new IllegalArgumentException("bootstrap must contain run, attempt, and context transitions in order");
        }
        if (!runStarted.candidateState().runId().equals(attemptStarted.candidateState().runId())
                || !runStarted.candidateState().runId().equals(contextIssued.candidateState().runId())
                || !runStarted.event().runId().equals(attemptStarted.event().runId())
                || !runStarted.event().runId().equals(contextIssued.event().runId())) {
            throw new IllegalArgumentException("bootstrap transitions must belong to one run");
        }
        if (runStarted.candidateState().stateRevision() != runStarted.event().expectedStateRevision() + 1
                || runStarted.candidateState().stateRevision() + 1 != attemptStarted.candidateState().stateRevision()
                || attemptStarted.candidateState().stateRevision() + 1 != contextIssued.candidateState().stateRevision()
                || attemptStarted.event().expectedStateRevision() != runStarted.candidateState().stateRevision()
                || contextIssued.event().expectedStateRevision() != attemptStarted.candidateState().stateRevision()) {
            throw new IllegalArgumentException("bootstrap transition revisions must be sequential");
        }
    }

    public AgentTransition finalTransition() {
        return contextIssued;
    }
}
