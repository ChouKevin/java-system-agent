package com.java.system.agent.answering.domain.run;

/**
 * 整個 validated action loop 的最終結果
 */
public enum RunOutcome {
    COMPLETED,
    INCONCLUSIVE,
    FAILED,
    CANCELLED
}
