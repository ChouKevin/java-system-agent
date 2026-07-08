package com.java.system.agent.ai.config;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.stream.IntStream;

/** 固定分段鎖，避免 MessageWindowChatMemory 的 read-modify-write 被同 conversation 並發覆蓋。 */
@Component
public class ChatMemoryLocks {

    private static final int STRIPE_COUNT = 1024;

    private final Object[] locks = IntStream.range(0, STRIPE_COUNT)
            .mapToObj(index -> new Object())
            .toArray();

    public void withConversationLock(String conversationId, Runnable action) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        Assert.notNull(action, "action must not be null");

        synchronized (lockFor(conversationId)) {
            action.run();
        }
    }

    Object lockFor(String conversationId) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        return locks[Math.floorMod(conversationId.hashCode(), locks.length)];
    }
}
