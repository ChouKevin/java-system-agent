package com.java.system.agent.ai.trace;

import com.java.system.agent.ai.loop.LoopTrace;

import java.util.List;
import java.util.Objects;

public record AgentTracePayload(
        List<MemoryMessageSnapshot> memorySnapshot,
        LoopTrace loopTrace) {

    public AgentTracePayload {
        memorySnapshot = List.copyOf(memorySnapshot);
        loopTrace = Objects.requireNonNull(loopTrace, "loopTrace must not be null");
    }
}
