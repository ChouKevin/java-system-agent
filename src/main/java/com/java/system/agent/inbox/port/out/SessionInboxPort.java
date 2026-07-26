package com.java.system.agent.inbox.port.out;

import com.java.system.agent.inbox.domain.InboxEnqueueRequest;
import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.inbox.domain.InboxMessage;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable session inbox 的原子持久化外部邊界
 *
 */
public interface SessionInboxPort {

    /**
     * 原子解析或建立 session、依來源訊息去重、配發 session 內 sequence，並在必要時配發 inbox、run 與
     * session ID 後插入訊息
     *
     * <p>不得以額外的 session registry contract 拆開這個操作</p>
     */
    InboxMessage enqueue(InboxEnqueueRequest request);

    /**
     * 原子選取到期且符合資格的 PENDING session head，將其轉為 PROCESSING、遞增 attemptCount、設定
     * claimedAt 為 now，並回傳認領後狀態
     *
     * <p>同一 session 後續 sequence 不得跨越較早的 PENDING 或 PROCESSING 訊息</p>
     */
    Optional<InboxMessage> claimNext(Instant now);

    /**
     * 以精確的已認領 identity、run、session、目前 PROCESSING 狀態與 attempt 作為 guard，終止標記為
     * COMPLETED，並清除既有 failure metadata
     *
     * <p>過期或不再是目前認領的訊息屬於 contract conflict</p>
     */
    void complete(InboxMessage claimedMessage, Instant completedAt);

    /**
     * 以精確的已認領 identity、run、session、目前 PROCESSING 狀態與 attempt 作為 guard，回到 PENDING，
     * 清除 claimedAt、記錄有界 failure，並設定 supplied availableAt
     *
     * <p>必須保留 ID、sequence、question 與 attempt，過期或不再是目前認領的訊息屬於 contract conflict</p>
     */
    void retry(InboxMessage claimedMessage, InboxFailure failure, Instant availableAt);

    /**
     * 以精確的已認領 identity、run、session、目前 PROCESSING 狀態與 attempt 作為 guard，終止標記為 FAILED，
     * 並記錄有界 failure
     *
     * <p>過期或不再是目前認領的訊息屬於 contract conflict</p>
     */
    void fail(InboxMessage claimedMessage, InboxFailure failure, Instant failedAt);

    /**
     * 將全部 PROCESSING 訊息轉為在 recoveredAt 到期的 PENDING，清除 claimedAt、保留 attemptCount 與 failure，
     * 並回傳復原筆數
     */
    int recoverInterrupted(Instant recoveredAt);
}
