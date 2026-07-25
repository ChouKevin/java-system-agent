package com.java.system.agent.runtime.application.semantic;

/**
 * {@link SemanticResultInterpreter} 對一次語意查詢的判定，決定
 * {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 接下來要怎麼做
 */
public enum SemanticStepOutcome {

    /**
     * 證據已被完整接受、information need 已解決，迴圈可以繼續規劃下一個 need
     */
    PROGRESSED,

    /**
     * 只有部分證據可用，已接受為有效證據並記一筆警告，但 need 仍維持 pending，
     * 迴圈會繼續往下走並在下一輪重新規劃同一個 need
     */
    PARTIAL,

    /**
     * 屬於暫時性失敗（NOT_READY/TIMEOUT）且允許重試，迴圈會在同一輪立即重打一次
     * 這個查詢，不消耗額外的迴圈迭代
     */
    RETRYABLE,

    /**
     * 偵測到 repository revision 已經漂移，迴圈會交回 lifecycle 重啟這個 attempt
     * （最多一次），超過重啟上限則以 {@code REVISION_RESTART_LIMIT} 終止 run
     */
    STALE,

    /**
     * 不可重試的語意層級問題（目標歧義、被禁止、能力缺漏，或暫時性失敗已無法再重試），
     * 迴圈以 {@code INCONCLUSIVE} 終止 run
     */
    BLOCKED,

    /**
     * 協定層級的硬性失敗（protocol error、引擎失敗），迴圈以 {@code FAILED} 終止 run
     */
    FAILED
}
