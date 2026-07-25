package com.java.system.agent.runtime.domain.run;

/**
 * Attempt 的狀態機
 *
 * <p>只描述單一 Attempt 內部的推進階段，Run 層級的階段不在此表示，
 * 由 {@link RunOutcome} 另外承載</p>
 */
public enum AttemptStatus {
    RECEIVED,
    REVISION_PINNING,
    PLANNING,
    EXECUTING,
    STALE,
    COMPLETED,
    INCONCLUSIVE,
    FAILED,
    CANCELLED
}
