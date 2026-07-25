package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.run.AttemptState;

import java.util.Objects;

/**
 * 不需要追蹤已提交狀態的 {@link CommitTracker} 實作
 *
 * <p>由 {@link SemanticResultInterpreter#handle} 使用——呼叫端不需要在提交失敗時取得
 * 最後一次成功的狀態</p>
 */
enum NoopCommitTracker implements CommitTracker {
    INSTANCE;

    @Override
    public void record(AttemptState committedState) {
        Objects.requireNonNull(committedState, "committed analysis state must not be null");
    }
}
