package com.java.system.agent.ai.controller;

import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.trace.LoopTraceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TraceDebugControllerTest {

    private FakeLoopTraceStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new FakeLoopTraceStore();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TraceDebugController(store))
                .build();
    }

    @Test
    void recentByConversation() throws Exception {
        store.save("c1", trace("t1"));
        store.save("c1", trace("t2"));

        mockMvc.perform(get("/debug/trace/c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value("t2"));
    }

    @Test
    void byTraceId_returnsTraceWhenPresent() throws Exception {
        store.save("c1", trace("t1"));

        mockMvc.perform(get("/debug/trace/id/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("t1"));
    }

    @Test
    void byTraceId_404WhenMissing() throws Exception {
        mockMvc.perform(get("/debug/trace/id/nope"))
                .andExpect(status().isNotFound());
    }

    private static LoopTrace trace(String traceId) {
        return new LoopTrace(traceId, "analyst", "答案", true, List.of(), List.of());
    }

    private static final class FakeLoopTraceStore implements LoopTraceStore {

        private final Map<String, List<LoopTrace>> byConversation = new HashMap<>();
        private final Map<String, LoopTrace> byTraceId = new HashMap<>();

        @Override
        public void save(String conversationId, LoopTrace trace) {
            List<LoopTrace> existing = byConversation.getOrDefault(conversationId, List.of());
            List<LoopTrace> next = new ArrayList<>();
            next.add(trace);
            next.addAll(existing);
            byConversation.put(conversationId, next);
            byTraceId.put(trace.traceId(), trace);
        }

        @Override
        public List<LoopTrace> recent(String conversationId) {
            return byConversation.getOrDefault(conversationId, List.of());
        }

        @Override
        public Optional<LoopTrace> byTraceId(String traceId) {
            return Optional.ofNullable(byTraceId.get(traceId));
        }
    }
}
