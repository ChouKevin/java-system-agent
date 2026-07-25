package com.java.system.agent.runtime.domain.run;

/**
 * 單一固定 revision 那一輪嘗試的結果
 *
 * <p>與 {@link RunOutcome} 成對：{@code AttemptOutcome} 是一輪嘗試的結果，
 * {@code RunOutcome} 才是整個問題的最終答案
 * 多出的 {@code STALE} 代表 revision 在嘗試過程中改變，此輪作廢並由新 Attempt 取代</p>
 */
public enum AttemptOutcome {
    COMPLETED,
    STALE,
    INCONCLUSIVE,
    FAILED,
    CANCELLED
}
