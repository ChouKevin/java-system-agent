package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AttemptState;

import java.util.Objects;

/**
 * 一次 attempt 執行過程中隨身攜帶的三個座標：所屬 run、目前狀態與 revision 重啟次數
 *
 * <p>{@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 迴圈的每一步都
 * 讀取並替換這個值；實際的 run/state 一致性由
 * {@link AttemptLifecycleManager} 的操作保證，這裡的建構檢查只確保三個座標彼此對得上</p>
 *
 * <p>{@code revisionRestartCount} 只允許 0 或 1，因為單一 attempt 只容許一次 revision
 * mismatch 重啟</p>
 */
public record AttemptLifecycle(
        AnalysisRun run,
        AttemptState state,
        int revisionRestartCount) {

    public AttemptLifecycle {
        Objects.requireNonNull(run, "analysis run must not be null");
        Objects.requireNonNull(state, "analysis state must not be null");
        if (revisionRestartCount < 0 || revisionRestartCount > 1) {
            throw new IllegalArgumentException("revision restart count must be between zero and one");
        }
        if (!run.id().equals(state.runId())) {
            throw new IllegalArgumentException("analysis run and state must belong to the same run");
        }
        if (!run.currentAttempt().id().equals(state.attemptId())) {
            throw new IllegalArgumentException("analysis run current attempt and state must match");
        }
    }
}
