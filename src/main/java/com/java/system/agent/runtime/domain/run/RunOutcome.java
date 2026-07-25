package com.java.system.agent.runtime.domain.run;

/**
 * 整個 Run 的最終結果，也就是對使用者問題的答案
 *
 * <p>與 {@link AttemptOutcome} 成對：{@code RunOutcome} 是整個問題的答案，
 * {@code AttemptOutcome} 是單一固定 revision 那一輪嘗試的結果</p>
 */
public enum RunOutcome {
    COMPLETED,
    INCONCLUSIVE,
    FAILED,
    CANCELLED
}
