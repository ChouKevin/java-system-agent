package com.java.system.agent.ai.loop.trace;

import com.java.system.agent.ai.loop.LoopTrace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 記憶體環狀緩衝:每個 conversationId 保留最近 N 棵 trace tree */
@Component
public class InMemoryLoopTraceStore implements LoopTraceStore {

    private final int retain;
    private final int maxConversations;
    private final Map<String, Deque<LoopTrace>> byConversation = new ConcurrentHashMap<>();
    private final Map<String, LoopTrace> byTraceId = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Boolean> conversationLru = new LinkedHashMap<>(16, 0.75f, true);

    public InMemoryLoopTraceStore(@Value("${agent.loop.trace.retain:20}") int retain,
                                  @Value("${agent.loop.trace.max-conversations:200}") int maxConversations) {
        this.retain = retain;
        this.maxConversations = maxConversations;
    }

    @Override
    public synchronized void save(String conversationId, LoopTrace trace) {
        String safeConversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
        LoopTrace safeTrace = Objects.requireNonNull(trace, "trace must not be null");

        conversationLru.put(safeConversationId, Boolean.TRUE);
        Deque<LoopTrace> traces = byConversation.computeIfAbsent(safeConversationId, key -> new ArrayDeque<>());
        traces.addFirst(safeTrace);
        byTraceId.put(safeTrace.traceId(), safeTrace);
        while (traces.size() > retain) {
            LoopTrace evictedTrace = traces.removeLast();
            byTraceId.remove(evictedTrace.traceId());
        }
        evictOldConversations();
    }

    @Override
    public synchronized List<LoopTrace> recent(String conversationId) {
        Deque<LoopTrace> traces = byConversation.get(conversationId);
        if (Objects.isNull(traces)) {
            return List.of();
        }
        return new ArrayList<>(traces);
    }

    @Override
    public Optional<LoopTrace> byTraceId(String traceId) {
        return Optional.ofNullable(byTraceId.get(traceId));
    }

    private void evictOldConversations() {
        while (conversationLru.size() > maxConversations) {
            String evictedConversationId = conversationLru.keySet().iterator().next();
            conversationLru.remove(evictedConversationId);
            Deque<LoopTrace> evictedTraces = byConversation.remove(evictedConversationId);
            if (Objects.nonNull(evictedTraces)) {
                evictedTraces.forEach(trace -> byTraceId.remove(trace.traceId()));
            }
        }
    }
}
