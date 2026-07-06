package com.java.system.agent.ai.loop.trace;

import com.java.system.agent.ai.loop.LoopTrace;

import java.util.List;
import java.util.Optional;

public interface LoopTraceStore {

    void save(String conversationId, LoopTrace trace);

    List<LoopTrace> recent(String conversationId);

    Optional<LoopTrace> byTraceId(String traceId);
}
