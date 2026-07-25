package com.java.system.agent.runtime.application.lifecycle;

import java.util.Objects;

/**
 * lifecycle 操作依賴的外部協作者（revision port、attempt ID 產生器、取消查詢或狀態提交）
 * 拋出非預期例外時，統一包裝後對外拋出
 *
 * <p>由 {@link AttemptLifecycleManager} 在呼叫這些外部依賴失敗時建立，攜帶最後一次成功
 * 提交的 {@link AttemptLifecycle} 與原始例外作為 cause，使呼叫端能以一致的方式
 * 收斂為執行失敗</p>
 */
public final class AttemptLifecycleExternalFailureException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;

    public AttemptLifecycleExternalFailureException(
            String message,
            AttemptLifecycle lastCommittedLifecycle,
            RuntimeException cause) {
        super(message, Objects.requireNonNull(cause, "external failure cause must not be null"));
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
    }

    public AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }
}
