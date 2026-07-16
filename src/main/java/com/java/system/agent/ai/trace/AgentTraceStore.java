package com.java.system.agent.ai.trace;

import java.util.List;
import java.util.Optional;

public interface AgentTraceStore {

    void save(AgentTraceRecord trace);

    List<AgentTraceSummary> search(TraceSearchQuery query);

    List<AgentTraceRecord> conversation(String conversationId);

    List<AgentTraceRecord> recent(String conversationId);

    Optional<AgentTraceRecord> byTraceId(String traceId);
}
