package com.java.system.agent.ai.trace;

import com.java.system.agent.ai.loop.TerminationReason;

import java.time.Instant;
import java.util.Objects;

public record AgentTraceSummary(
        Instant createdAt,
        String traceId,
        Instant completedAt,
        String conversationId,
        String userId,
        String eventId,
        String userQuery,
        boolean accepted,
        TerminationReason terminationReason,
        int iterationCount,
        long rejectionCount,
        long promptTokens,
        long completionTokens,
        long durationMillis) {

    public static AgentTraceSummary from(AgentTraceRecord trace) {
        AgentTraceRecord safeTrace = Objects.requireNonNull(trace, "trace must not be null");
        return new AgentTraceSummary(
                safeTrace.createdAt(),
                safeTrace.traceId(),
                safeTrace.completedAt(),
                safeTrace.conversationId(),
                safeTrace.userId(),
                safeTrace.eventId(),
                safeTrace.userQuery(),
                safeTrace.accepted(),
                safeTrace.terminationReason(),
                safeTrace.iterationCount(),
                safeTrace.rejectionCount(),
                safeTrace.promptTokens(),
                safeTrace.completionTokens(),
                safeTrace.durationMillis());
    }
}
