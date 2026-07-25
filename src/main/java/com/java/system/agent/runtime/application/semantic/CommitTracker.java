package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.run.AttemptState;

/**
 * {@link SemanticResultInterpreter} 每次提交狀態變更時，記錄最後一次已提交狀態的觀察者
 *
 * <p>{@link NoopCommitTracker} 用於呼叫端不需要追蹤已提交狀態的路徑；
 * {@link TrackingCommitTracker} 用於需要在提交失敗時回報最後一次成功狀態的執行路徑</p>
 */
interface CommitTracker {

    void record(AttemptState committedState);
}
