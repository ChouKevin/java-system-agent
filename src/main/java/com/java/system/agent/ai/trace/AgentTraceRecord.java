package com.java.system.agent.ai.trace;

import com.java.system.agent.ai.loop.TerminationReason;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Objects;

public record AgentTraceRecord(
        Instant createdAt,
        String traceId,
        Instant completedAt,
        String conversationId,
        String userId,
        String teamId,
        String channelId,
        String eventId,
        String userQuery,
        String finalCandidate,
        String slackResponse,
        boolean accepted,
        TerminationReason terminationReason,
        int iterationCount,
        long rejectionCount,
        long promptTokens,
        long completionTokens,
        long durationMillis,
        AgentTracePayload payload) {

    public AgentTraceRecord {
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        Assert.hasText(traceId, "traceId must not be blank");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        Assert.hasText(conversationId, "conversationId must not be blank");
        userId = Objects.toString(userId, "");
        teamId = Objects.toString(teamId, "");
        channelId = Objects.toString(channelId, "");
        eventId = Objects.toString(eventId, "");
        Assert.hasText(userQuery, "userQuery must not be blank");
        finalCandidate = Objects.toString(finalCandidate, "");
        slackResponse = Objects.toString(slackResponse, "");
        terminationReason = Objects.requireNonNull(
                terminationReason, "terminationReason must not be null");
        Assert.isTrue(iterationCount >= 0, "iterationCount must not be negative");
        Assert.isTrue(rejectionCount >= 0, "rejectionCount must not be negative");
        Assert.isTrue(promptTokens >= 0, "promptTokens must not be negative");
        Assert.isTrue(completionTokens >= 0, "completionTokens must not be negative");
        Assert.isTrue(durationMillis >= 0, "durationMillis must not be negative");
        payload = Objects.requireNonNull(payload, "payload must not be null");
    }
}
