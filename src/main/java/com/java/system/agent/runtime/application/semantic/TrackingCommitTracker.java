package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.run.AttemptState;

import java.util.Objects;

/**
 * 每次提交都記住最後一次已提交狀態的 {@link CommitTracker} 實作
 *
 * <p>由 {@link SemanticResultInterpreter#handleForExecution} 使用——提交失敗時，
 * {@link SemanticResultHandlingException} 需要帶著最後一次成功提交的狀態</p>
 */
final class TrackingCommitTracker implements CommitTracker {

    private AttemptState lastCommittedState;

    TrackingCommitTracker(AttemptState initialState) {
        lastCommittedState = Objects.requireNonNull(initialState, "analysis state must not be null");
    }

    @Override
    public void record(AttemptState committedState) {
        lastCommittedState = Objects.requireNonNull(
                committedState, "committed analysis state must not be null");
    }

    AttemptState lastCommittedState() {
        return lastCommittedState;
    }
}
