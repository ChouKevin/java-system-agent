package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.application.state.AnalysisTransitionCommitException;
import com.java.system.agent.runtime.domain.run.AttemptState;

import java.util.Objects;

/**
 * {@link SemanticResultInterpreter#handleForExecution} 在解讀語意結果過程中，狀態變更
 * 提交失敗時拋出
 *
 * <p>攜帶目前為止最後一次成功提交的 {@link AttemptState}，讓呼叫端能以一致的方式
 * 收斂為執行失敗</p>
 */
public final class SemanticResultHandlingException extends RuntimeException {

    private final AttemptState lastCommittedState;

    SemanticResultHandlingException(
            AttemptState lastCommittedState,
            AnalysisTransitionCommitException cause) {
        super("semantic result transition could not be committed", cause);
        this.lastCommittedState = Objects.requireNonNull(
                lastCommittedState, "last committed analysis state must not be null");
    }

    public AttemptState lastCommittedState() {
        return lastCommittedState;
    }
}
