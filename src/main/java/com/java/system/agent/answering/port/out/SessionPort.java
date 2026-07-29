package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.conversation.SessionId;

/**
 * 讀取與 append 已接受 ConversationTurn 的 session 外部邊界
 */
public interface SessionPort {

    SessionHistory read(SessionId sessionId);

    /**
     * 以 sessionId 與 turn.runId() 作為 idempotency key append
     * 完全相同的 turn 必須是 no-op，不同內容必須失敗
     */
    void append(SessionId sessionId, ConversationTurn turn);
}
