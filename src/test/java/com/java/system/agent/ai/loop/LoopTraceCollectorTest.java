package com.java.system.agent.ai.loop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoopTraceCollectorTest {

    @Test
    void drain_returnsAddedTracesThenClears() {
        LoopTraceCollector collector = new LoopTraceCollector();
        collector.add(trace("t1"));
        collector.add(trace("t2"));

        assertThat(collector.drain()).extracting(LoopTrace::traceId).containsExactly("t1", "t2");
        assertThat(collector.drain()).isEmpty();
    }

    private static LoopTrace trace(String traceId) {
        return new LoopTrace(traceId, "translator", "a", true, List.of(), List.of());
    }
}
