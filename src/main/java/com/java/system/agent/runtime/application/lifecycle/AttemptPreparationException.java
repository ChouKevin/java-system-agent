package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.port.out.SemanticFailure;

import java.util.Objects;

/**
 * 準備 attempt 期間，repository revision 查詢回傳失敗結果時拋出
 *
 * <p>由 {@link AttemptLifecycleManager} 在 revision probe 沒有取得可用 revision 時建立，
 * 攜帶最後一次成功提交的 {@link AttemptLifecycle} 與造成失敗的
 * {@code SemanticFailure}，供呼叫端決定要以哪種終止原因收斂</p>
 */
public final class AttemptPreparationException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;
    private final SemanticFailure semanticFailure;

    public AttemptPreparationException(
            AttemptLifecycle lastCommittedLifecycle,
            SemanticFailure semanticFailure) {
        super("repository revision preparation is unavailable");
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
        Objects.requireNonNull(semanticFailure, "semantic failure must not be null");
        this.semanticFailure = new SemanticFailure(
                semanticFailure.code(), semanticFailure.message(), semanticFailure.retryable());
    }

    public AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }

    public SemanticFailure semanticFailure() {
        return semanticFailure;
    }
}
