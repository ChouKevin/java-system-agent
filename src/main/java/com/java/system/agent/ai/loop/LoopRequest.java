package com.java.system.agent.ai.loop;

import java.util.UUID;

public record LoopRequest(String traceId, String conversationId, String userQuery) {

    public LoopRequest(String conversationId, String userQuery) {
        this(UUID.randomUUID().toString(), conversationId, userQuery);
    }
}
