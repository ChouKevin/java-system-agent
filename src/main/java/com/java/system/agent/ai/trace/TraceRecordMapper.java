package com.java.system.agent.ai.trace;

import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.StepMetrics;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Component
public class TraceRecordMapper {

    public AgentTraceRecord map(
            AgentRequestContext context,
            List<Message> memorySnapshot,
            LoopTrace loopTrace,
            String slackResponse,
            Instant completedAt) {
        AgentRequestContext safeContext = Objects.requireNonNull(context, "context must not be null");
        List<Message> safeMemorySnapshot = List.copyOf(memorySnapshot);
        LoopTrace safeLoopTrace = Objects.requireNonNull(loopTrace, "loopTrace must not be null");
        Instant safeCompletedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        TraceMetrics metrics = metrics(safeLoopTrace);
        long durationMillis = Math.max(
                0L, Duration.between(safeContext.startedAt(), safeCompletedAt).toMillis());

        return new AgentTraceRecord(
                safeContext.startedAt(),
                safeContext.traceId(),
                safeCompletedAt,
                safeContext.conversationId(),
                safeContext.userId(),
                safeContext.teamId(),
                safeContext.channelId(),
                safeContext.eventId(),
                safeContext.userQuery(),
                finalCandidate(safeLoopTrace),
                slackResponse,
                safeLoopTrace.accepted(),
                safeLoopTrace.terminationReason(),
                safeLoopTrace.iterationCount(),
                safeLoopTrace.rejectionCount(),
                metrics.promptTokens(),
                metrics.completionTokens(),
                durationMillis,
                new AgentTracePayload(memorySnapshots(safeMemorySnapshot), safeLoopTrace));
    }

    private List<MemoryMessageSnapshot> memorySnapshots(List<Message> messages) {
        return messages.stream()
                .map(message -> new MemoryMessageSnapshot(
                        message.getMessageType().name(), message.getText()))
                .toList();
    }

    private String finalCandidate(LoopTrace trace) {
        List<LoopStep> steps = trace.steps();
        for (int index = steps.size() - 1; index >= 0; index--) {
            String candidate = steps.get(index).candidateAnswer();
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private TraceMetrics metrics(LoopTrace trace) {
        long promptTokens = 0L;
        long completionTokens = 0L;
        for (LoopStep step : trace.steps()) {
            StepMetrics stepMetrics = step.metrics();
            promptTokens += stepMetrics.promptTokens();
            completionTokens += stepMetrics.completionTokens();
            for (LoopTrace childTrace : step.childTraces()) {
                TraceMetrics childMetrics = metrics(childTrace);
                promptTokens += childMetrics.promptTokens();
                completionTokens += childMetrics.completionTokens();
            }
        }
        return new TraceMetrics(promptTokens, completionTokens);
    }

    private record TraceMetrics(long promptTokens, long completionTokens) {
    }
}
