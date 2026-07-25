package com.java.system.agent.runtime.application.lifecycle;

import java.util.Objects;

/**
 * 準備 attempt 期間偵測到取消請求時拋出
 *
 * <p>由 {@link AttemptLifecycleManager} 在每次 revision probe 前檢查
 * {@code AnalysisCancellationPort} 得知該 run 已被要求取消時建立，攜帶最後一次成功
 * 提交的 {@link AttemptLifecycle}</p>
 */
public final class AttemptPreparationCancelledException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;

    AttemptPreparationCancelledException(AttemptLifecycle lastCommittedLifecycle) {
        super("analysis cancellation was requested during revision preparation");
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
    }

    public AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }
}
