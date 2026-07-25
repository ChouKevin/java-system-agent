package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.conversation.ConversationContext;
import com.java.system.agent.runtime.domain.conversation.ConversationId;
import com.java.system.agent.runtime.port.out.ConversationContextPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ConversationContextPort} 的測試替身，以 map 模擬儲存，未知的 conversation
 * 回傳 {@link ConversationContext#empty()}，與正式 port 的契約一致
 */
public final class FakeConversationContextAdapter implements ConversationContextPort {

    private final Map<ConversationId, ConversationContext> contexts = new HashMap<>();

    @Override
    public synchronized ConversationContext load(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversation ID must not be null");
        return contexts.getOrDefault(conversationId, ConversationContext.empty());
    }

    @Override
    public synchronized void save(ConversationId conversationId, ConversationContext context) {
        Objects.requireNonNull(conversationId, "conversation ID must not be null");
        Objects.requireNonNull(context, "conversation context must not be null");
        contexts.put(conversationId, context);
    }
}
