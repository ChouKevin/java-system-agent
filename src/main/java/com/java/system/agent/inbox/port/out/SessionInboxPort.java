package com.java.system.agent.inbox.port.out;

import com.java.system.agent.inbox.domain.InboxClaim;
import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable session inbox 的原子持久化外部邊界
 *
 */
public interface SessionInboxPort {

    /**
     * 原子選取到期且符合資格的 PENDING session head，將其轉為 PROCESSING、遞增 attemptCount、設定
     * claimedAt 為 now，並回傳認領後狀態
     *
     * <p>同一 session 後續 sequence 不得跨越較早的 PENDING 或 PROCESSING 訊息</p>
     */
    Optional<InboxClaim> claimNext(Instant now);

    /**
     * 以精確的已認領 identity、run、session、目前 PROCESSING 狀態與 attempt 作為 guard，終止標記為
     * COMPLETED，並清除既有 failure metadata
     *
     * <p>過期或不再是目前認領的訊息屬於 contract conflict</p>
     */
    void completeWithFinal(InboxClaim claim, AnswerQuestionResult result, Instant completedAt);

    /**
     * 以精確的已認領 identity、run、session、目前 PROCESSING 狀態與 attempt 作為 guard，回到 PENDING，
     * 清除 claimedAt、記錄有界 failure，並設定 supplied availableAt
     *
     * <p>必須保留 ID、sequence、question 與 attempt，過期或不再是目前認領的訊息屬於 contract conflict</p>
     */
    void retry(InboxClaim claim, InboxFailure failure, Instant availableAt);

    /**
     * 以精確的已認領 identity、run、session、目前 PROCESSING 狀態與 attempt 作為 guard，終止標記為 FAILED，
     * 並記錄有界 failure
     *
     * <p>過期或不再是目前認領的訊息屬於 contract conflict</p>
     */
    void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt);

    /**
     * 將已認領訊息以模型容量原因延後，並保留原有 attempt
     */
    void deferForCapacity(InboxClaim claim, Instant retryAt);

    /**
     * 僅在精確 claim 仍為 PROCESSING 時將它復原為 PENDING，並保留其 identity 與 attempt
     *
     * <p>回傳 false 表示該 claim 已不再是可安全復原的目前 claim</p>
     */
    default boolean recoverClaim(InboxClaim claim, Instant recoveredAt) {
        throw new UnsupportedOperationException("claim-specific recovery is not available");
    }

    /**
     * 將全部 PROCESSING 訊息轉為在 recoveredAt 到期的 PENDING，清除 claimedAt、保留 attemptCount 與 failure，
     * 並回傳復原筆數
     */
    int recoverInterrupted(Instant recoveredAt);
}
