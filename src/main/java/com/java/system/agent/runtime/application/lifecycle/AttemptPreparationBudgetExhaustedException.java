package com.java.system.agent.runtime.application.lifecycle;

import java.util.Objects;

/**
 * 準備 attempt 期間，尚未釘選完所有 repository revision 就已耗盡 step 預算時拋出
 *
 * <p>由 {@link AttemptLifecycleManager} 在逐一釘選 scope 內 repository revision 前檢查
 * 預算，發現無剩餘 step 可用時建立，攜帶最後一次成功提交的 {@link AttemptLifecycle}</p>
 */
public final class AttemptPreparationBudgetExhaustedException extends RuntimeException {

    private final AttemptLifecycle lastCommittedLifecycle;

    AttemptPreparationBudgetExhaustedException(AttemptLifecycle lastCommittedLifecycle) {
        super("analysis step budget is exhausted during revision preparation");
        this.lastCommittedLifecycle = Objects.requireNonNull(
                lastCommittedLifecycle, "last committed attempt lifecycle must not be null");
    }

    public AttemptLifecycle lastCommittedLifecycle() {
        return lastCommittedLifecycle;
    }
}
