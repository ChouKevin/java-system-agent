package com.java.system.agent.ai.config;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** conversation 數量以 LRU 封頂的記憶體 ChatMemoryRepository，防止長時間執行洩漏。 */
public class LruChatMemoryRepository implements ChatMemoryRepository {

    private final int maxConversations;
    private final LinkedHashMap<String, List<Message>> store = new LinkedHashMap<>(16, 0.75f, true);

    public LruChatMemoryRepository(int maxConversations) {
        Assert.isTrue(maxConversations > 0, "maxConversations must be positive");
        this.maxConversations = maxConversations;
    }

    @Override
    public synchronized List<String> findConversationIds() {
        return List.copyOf(store.keySet());
    }

    @Override
    public synchronized List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        List<Message> messages = store.get(conversationId);
        return Objects.isNull(messages) ? List.of() : List.copyOf(messages);
    }

    @Override
    public synchronized void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        Assert.notNull(messages, "messages must not be null");
        Assert.noNullElements(messages, "messages must not contain null elements");

        store.put(conversationId, new ArrayList<>(messages));
        evictOldConversations();
    }

    @Override
    public synchronized void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        store.remove(conversationId);
    }

    private void evictOldConversations() {
        while (store.size() > maxConversations) {
            String eldest = store.keySet().iterator().next();
            store.remove(eldest);
        }
    }
}
