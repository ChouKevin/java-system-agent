package com.java.system.agent.ai.trace;

import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

public class InMemoryAgentTraceStore implements AgentTraceStore {

    private static final Comparator<AgentTraceRecord> CHRONOLOGICAL_ORDER = Comparator
            .comparing(AgentTraceRecord::createdAt)
            .thenComparing(AgentTraceRecord::traceId);
    private static final Comparator<AgentTraceRecord> REVERSE_CHRONOLOGICAL_ORDER =
            CHRONOLOGICAL_ORDER.reversed();

    private final int retain;
    private final int maxConversations;
    private final Map<String, NavigableSet<AgentTraceRecord>> byConversation = new HashMap<>();
    private final Map<String, AgentTraceRecord> byTraceId = new HashMap<>();
    private final LinkedHashMap<String, Boolean> conversationLru =
            new LinkedHashMap<>(16, 0.75f, true);

    InMemoryAgentTraceStore(int retain, int maxConversations) {
        Assert.isTrue(retain > 0, "retain must be greater than zero");
        Assert.isTrue(maxConversations > 0, "maxConversations must be greater than zero");
        this.retain = retain;
        this.maxConversations = maxConversations;
    }

    @Override
    public synchronized void save(AgentTraceRecord trace) {
        AgentTraceRecord safeTrace = Objects.requireNonNull(trace, "trace must not be null");
        removeExistingTrace(safeTrace.traceId());

        String conversationId = safeTrace.conversationId();
        conversationLru.put(conversationId, Boolean.TRUE);
        NavigableSet<AgentTraceRecord> traces = byConversation.computeIfAbsent(
                conversationId, key -> new TreeSet<>(CHRONOLOGICAL_ORDER));
        traces.add(safeTrace);
        byTraceId.put(safeTrace.traceId(), safeTrace);

        while (traces.size() > retain) {
            AgentTraceRecord evictedTrace = Objects.requireNonNull(
                    traces.pollFirst(), "oldest trace must exist");
            byTraceId.remove(evictedTrace.traceId(), evictedTrace);
        }
        evictOldConversations();
    }

    @Override
    public synchronized List<AgentTraceSummary> search(TraceSearchQuery query) {
        TraceSearchQuery safeQuery = Objects.requireNonNull(query, "query must not be null");
        return byTraceId.values().stream()
                .filter(trace -> matches(trace, safeQuery))
                .sorted(REVERSE_CHRONOLOGICAL_ORDER)
                .limit(safeQuery.limit())
                .map(AgentTraceSummary::from)
                .toList();
    }

    @Override
    public synchronized List<AgentTraceRecord> conversation(String conversationId) {
        NavigableSet<AgentTraceRecord> traces = byConversation.get(conversationId);
        if (Objects.isNull(traces)) {
            return List.of();
        }
        return List.copyOf(traces);
    }

    @Override
    public synchronized List<AgentTraceRecord> recent(String conversationId) {
        NavigableSet<AgentTraceRecord> traces = byConversation.get(conversationId);
        if (Objects.isNull(traces)) {
            return List.of();
        }
        return List.copyOf(traces.descendingSet());
    }

    @Override
    public synchronized Optional<AgentTraceRecord> byTraceId(String traceId) {
        return Optional.ofNullable(byTraceId.get(traceId));
    }

    private boolean matches(AgentTraceRecord trace, TraceSearchQuery query) {
        return query.userId().map(trace.userId()::equals).orElse(true)
                && query.eventId().map(trace.eventId()::equals).orElse(true)
                && query.accepted().map(accepted -> accepted == trace.accepted()).orElse(true)
                && query.from().map(from -> !trace.createdAt().isBefore(from)).orElse(true)
                && query.to().map(to -> trace.createdAt().isBefore(to)).orElse(true);
    }

    private void removeExistingTrace(String traceId) {
        AgentTraceRecord existingTrace = byTraceId.remove(traceId);
        if (Objects.isNull(existingTrace)) {
            return;
        }

        String conversationId = existingTrace.conversationId();
        NavigableSet<AgentTraceRecord> traces = byConversation.get(conversationId);
        if (Objects.isNull(traces)) {
            return;
        }
        traces.removeIf(storedTrace -> storedTrace.traceId().equals(traceId));
        if (CollectionUtils.isEmpty(traces)) {
            byConversation.remove(conversationId);
            conversationLru.remove(conversationId);
        }
    }

    private void evictOldConversations() {
        while (conversationLru.size() > maxConversations) {
            String evictedConversationId = conversationLru.keySet().iterator().next();
            conversationLru.remove(evictedConversationId);
            NavigableSet<AgentTraceRecord> evictedTraces =
                    byConversation.remove(evictedConversationId);
            if (Objects.nonNull(evictedTraces)) {
                evictedTraces.forEach(trace -> byTraceId.remove(trace.traceId(), trace));
            }
        }
    }
}
