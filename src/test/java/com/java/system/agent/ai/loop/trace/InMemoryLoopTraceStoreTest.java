package com.java.system.agent.ai.loop.trace;

import com.java.system.agent.ai.loop.LoopTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLoopTraceStoreTest {

    @Test
    void recent_isNewestFirst_andByIdWorks() {
        InMemoryLoopTraceStore store = new InMemoryLoopTraceStore(20, 200);
        store.save("c1", trace("t1"));
        store.save("c1", trace("t2"));

        assertThat(store.recent("c1")).extracting(LoopTrace::traceId).containsExactly("t2", "t1");
        assertThat(store.byTraceId("t1")).map(LoopTrace::traceId).contains("t1");
    }

    @Test
    void evictsOldestBeyondRetain() {
        InMemoryLoopTraceStore store = new InMemoryLoopTraceStore(2, 200);
        store.save("c1", trace("t1"));
        store.save("c1", trace("t2"));
        store.save("c1", trace("t3"));

        assertThat(store.recent("c1")).extracting(LoopTrace::traceId).containsExactly("t3", "t2");
        assertThat(store.byTraceId("t1")).isEmpty();
    }

    @Test
    void evictsOldestConversationBeyondMaxConversations() {
        InMemoryLoopTraceStore store = new InMemoryLoopTraceStore(20, 2);
        store.save("c1", trace("t1"));
        store.save("c2", trace("t2"));
        store.save("c3", trace("t3"));

        assertThat(store.recent("c1")).isEmpty();
        assertThat(store.byTraceId("t1")).isEmpty();
        assertThat(store.recent("c3")).extracting(LoopTrace::traceId).containsExactly("t3");
    }

    private static LoopTrace trace(String traceId) {
        return new LoopTrace(traceId, "analyst", "a", true, List.of(), List.of());
    }
}
