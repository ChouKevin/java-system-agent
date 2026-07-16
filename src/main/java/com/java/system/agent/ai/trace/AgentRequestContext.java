package com.java.system.agent.ai.trace;

import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentRequestContext(
        String traceId,
        String userId,
        String teamId,
        String channelId,
        String eventId,
        String conversationId,
        String userQuery,
        Instant startedAt) {

    public AgentRequestContext {
        Assert.hasText(traceId, "traceId must not be blank");
        Assert.hasText(conversationId, "conversationId must not be blank");
        Assert.hasText(userQuery, "userQuery must not be blank");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        userId = Objects.toString(userId, "");
        teamId = Objects.toString(teamId, "");
        channelId = Objects.toString(channelId, "");
        eventId = Objects.toString(eventId, "");
    }

    public static AgentRequestContext create(
            String traceId,
            String userId,
            String teamId,
            String channelId,
            String eventId,
            String conversationId,
            String userQuery) {
        return new AgentRequestContext(
                traceId,
                userId,
                teamId,
                channelId,
                eventId,
                conversationId,
                userQuery,
                Instant.now());
    }

    public static AgentRequestContext direct(String conversationId, String userQuery) {
        return new AgentRequestContext(
                UUID.randomUUID().toString(),
                "",
                "",
                "",
                "",
                conversationId,
                userQuery,
                Instant.now());
    }
}
