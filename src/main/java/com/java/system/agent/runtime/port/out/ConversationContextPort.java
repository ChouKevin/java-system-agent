package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.conversation.ConversationContext;
import com.java.system.agent.runtime.domain.conversation.ConversationId;

/**
 * 讀寫一個 Slack thread 對應的 {@link ConversationContext}
 *
 * <p>未知的 {@link ConversationId} 一律回傳 {@link ConversationContext#empty()}，
 * 而不是 null 或空的 {@code Optional}，呼叫端因此不需要為「從未見過這個 thread」
 * 另外分支處理</p>
 */
public interface ConversationContextPort {

    ConversationContext load(ConversationId conversationId);

    void save(ConversationId conversationId, ConversationContext context);
}
