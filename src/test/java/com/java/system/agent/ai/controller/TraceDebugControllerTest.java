package com.java.system.agent.ai.controller;

import com.java.system.agent.ai.loop.GateDecision;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.StepMetrics;
import com.java.system.agent.ai.loop.TerminationReason;
import com.java.system.agent.ai.loop.ToolCallRecord;
import com.java.system.agent.ai.loop.ToolResultObservation;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.trace.AgentTracePayload;
import com.java.system.agent.ai.trace.AgentTraceRecord;
import com.java.system.agent.ai.trace.AgentTraceStore;
import com.java.system.agent.ai.trace.AgentTraceSummary;
import com.java.system.agent.ai.trace.MemoryMessageSnapshot;
import com.java.system.agent.ai.trace.TraceSearchQuery;
import com.java.system.agent.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Profile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TraceDebugControllerTest {

    private static final Instant BASE_TIME = Instant.parse("2026-07-15T00:00:00Z");
    private static final String SENSITIVE_STORE_ERROR =
            "TraceStatus ARCHIVED; SQL SELECT failed; password=super-secret";

    private FakeAgentTraceStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new FakeAgentTraceStore();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TraceDebugController(store))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void search_forwardsEveryFilterAndReturnsNewestFirstSummariesWithoutPayload() throws Exception {
        store.searchResults = List.of(
                AgentTraceSummary.from(trace("trace-2", "thread-2", BASE_TIME.plusSeconds(2))),
                AgentTraceSummary.from(trace("trace-1", "thread-1", BASE_TIME)));

        mockMvc.perform(get("/debug/trace")
                        .param("userId", "U1")
                        .param("eventId", "E1")
                        .param("accepted", "false")
                        .param("from", "2026-07-01T00:00:00Z")
                        .param("to", "2026-08-01T00:00:00Z")
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value("trace-2"))
                .andExpect(jsonPath("$[1].traceId").value("trace-1"))
                .andExpect(jsonPath("$[0].payload").doesNotExist());

        TraceSearchQuery query = store.lastSearchQuery.orElseThrow();
        assertThat(query.userId()).contains("U1");
        assertThat(query.eventId()).contains("E1");
        assertThat(query.accepted()).contains(false);
        assertThat(query.from()).contains(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(query.to()).contains(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(query.limit()).isEqualTo(25);
    }

    @Test
    void search_normalizesBlankTextFiltersAndDefaultsLimit() throws Exception {
        mockMvc.perform(get("/debug/trace")
                        .param("userId", "  ")
                        .param("eventId", "\t"))
                .andExpect(status().isOk());

        TraceSearchQuery query = store.lastSearchQuery.orElseThrow();
        assertThat(query.userId()).isEmpty();
        assertThat(query.eventId()).isEmpty();
        assertThat(query.accepted()).isEmpty();
        assertThat(query.from()).isEmpty();
        assertThat(query.to()).isEmpty();
        assertThat(query.limit()).isEqualTo(50);
    }

    @Test
    void search_acceptsMaximumLimit() throws Exception {
        mockMvc.perform(get("/debug/trace").param("limit", "100"))
                .andExpect(status().isOk());

        assertThat(store.lastSearchQuery.orElseThrow().limit()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101"})
    void search_rejectsOutOfRangeLimit(String limit) throws Exception {
        mockMvc.perform(get("/debug/trace").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request argument"));
    }

    @Test
    void search_rejectsInvalidLimitType() throws Exception {
        mockMvc.perform(get("/debug/trace").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid parameter value"));
    }

    @Test
    void search_rejectsInvalidInstant() throws Exception {
        mockMvc.perform(get("/debug/trace").param("from", "not-an-instant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid parameter value"));
    }

    @Test
    void search_rejectsInvalidBoolean() throws Exception {
        mockMvc.perform(get("/debug/trace").param("accepted", "sometimes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid parameter value"));
    }

    @Test
    void search_rejectsInvalidTimeRange() throws Exception {
        mockMvc.perform(get("/debug/trace")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-07-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request argument"));
    }

    @Test
    void conversation_returnsCompleteRecordsInChronologicalOrder() throws Exception {
        store.conversations.put("123.456", List.of(
                trace("old", "123.456", BASE_TIME),
                trace("new", "123.456", BASE_TIME.plusSeconds(1))));

        mockMvc.perform(get("/debug/trace/conversations/123.456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value("old"))
                .andExpect(jsonPath("$[1].traceId").value("new"))
                .andExpect(jsonPath("$[1].finalCandidate").value("candidate-new"))
                .andExpect(jsonPath("$[1].slackResponse").value("slack-new"))
                .andExpect(jsonPath("$[1].payload.memorySnapshot[0].content").value("上一題"))
                .andExpect(jsonPath("$[1].payload.loopTrace.steps[0].candidateAnswer")
                        .value("candidate-new"))
                .andExpect(jsonPath("$[1].payload.loopTrace.steps[0].verdict.decisions[0].gateName")
                        .value("code-evidence"))
                .andExpect(jsonPath("$[1].payload.loopTrace.toolCalls[0].result.summary")
                        .value("tool-new"));
    }

    @Test
    void recentByConversation_preservesNewestFirstCompatibilityOrder() throws Exception {
        store.recent.put("thread-1", List.of(
                trace("new", "thread-1", BASE_TIME.plusSeconds(1)),
                trace("old", "thread-1", BASE_TIME)));

        mockMvc.perform(get("/debug/trace/thread-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].traceId").value("new"))
                .andExpect(jsonPath("$[1].traceId").value("old"));
    }

    @Test
    void byTraceId_returnsFullTraceWhenPresent() throws Exception {
        AgentTraceRecord trace = trace("trace-1", "thread-1", BASE_TIME);
        store.byTraceId.put(trace.traceId(), trace);

        mockMvc.perform(get("/debug/trace/id/trace-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.slackResponse").value("slack-trace-1"))
                .andExpect(jsonPath("$.payload.memorySnapshot[0].content").value("上一題"))
                .andExpect(jsonPath("$.payload.loopTrace.steps[0].candidateAnswer")
                        .value("candidate-trace-1"));
    }

    @Test
    void byTraceId_returns404WhenMissing() throws Exception {
        mockMvc.perform(get("/debug/trace/id/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void byTraceId_delegatesMalformedUuidToStoreAndReturns404() throws Exception {
        mockMvc.perform(get("/debug/trace/id/not-a-uuid"))
                .andExpect(status().isNotFound());

        assertThat(store.lastTraceId).contains("not-a-uuid");
    }

    @Test
    void search_storeIllegalArgument_returnsSanitized500Response() throws Exception {
        store.failSearch = true;

        mockMvc.perform(get("/debug/trace"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unexpected server error"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(content().string(not(containsString("TraceStatus"))))
                .andExpect(content().string(not(containsString("SQL SELECT"))))
                .andExpect(content().string(not(containsString("super-secret"))));
    }

    @Test
    void byTraceId_storeIllegalArgument_returnsSanitized500Response() throws Exception {
        store.failByTraceId = true;

        mockMvc.perform(get("/debug/trace/id/trace-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unexpected server error"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(content().string(not(containsString("TraceStatus"))))
                .andExpect(content().string(not(containsString("SQL SELECT"))))
                .andExpect(content().string(not(containsString("super-secret"))));
    }

    @Test
    void controller_isExposedInDevAndUat() {
        Profile profile = TraceDebugController.class.getAnnotation(Profile.class);

        assertThat(profile.value()).containsExactly("dev|uat");
    }

    private static AgentTraceRecord trace(
            String traceId,
            String conversationId,
            Instant createdAt) {
        GateDecision gateDecision = new GateDecision("code-evidence", true, "verified", 4);
        Verdict verdict = new Verdict(true, "", List.of(gateDecision));
        LoopStep step = new LoopStep(
                1,
                "complete",
                List.of("repo_search"),
                verdict,
                List.of(),
                new StepMetrics(12, 8, 3),
                "candidate-" + traceId);
        ToolResultObservation observation = new ToolResultObservation(
                "SUCCESS", "", 7, 24, "sha256", "tool-" + traceId);
        ToolCallRecord toolCall = new ToolCallRecord("repo_search", "{}", observation);
        LoopTrace loopTrace = new LoopTrace(
                traceId,
                "analyst",
                "candidate-" + traceId,
                true,
                List.of(step),
                List.of(toolCall),
                TerminationReason.ACCEPTED);
        AgentTracePayload payload = new AgentTracePayload(
                List.of(new MemoryMessageSnapshot("USER", "上一題")), loopTrace);
        return new AgentTraceRecord(
                createdAt,
                traceId,
                createdAt.plusMillis(25),
                conversationId,
                "U1",
                "T1",
                "C1",
                "E1",
                "question-" + traceId,
                "candidate-" + traceId,
                "slack-" + traceId,
                true,
                TerminationReason.ACCEPTED,
                1,
                0,
                8,
                3,
                25,
                payload);
    }

    private static final class FakeAgentTraceStore implements AgentTraceStore {

        private List<AgentTraceSummary> searchResults = List.of();
        private final Map<String, List<AgentTraceRecord>> conversations = new HashMap<>();
        private final Map<String, List<AgentTraceRecord>> recent = new HashMap<>();
        private final Map<String, AgentTraceRecord> byTraceId = new HashMap<>();
        private Optional<TraceSearchQuery> lastSearchQuery = Optional.empty();
        private Optional<String> lastTraceId = Optional.empty();
        private boolean failSearch;
        private boolean failByTraceId;

        @Override
        public void save(AgentTraceRecord trace) {
            throw new UnsupportedOperationException("save is not used by controller tests");
        }

        @Override
        public List<AgentTraceSummary> search(TraceSearchQuery query) {
            if (failSearch) {
                throw new IllegalArgumentException(SENSITIVE_STORE_ERROR);
            }
            lastSearchQuery = Optional.of(query);
            return searchResults;
        }

        @Override
        public List<AgentTraceRecord> conversation(String conversationId) {
            return conversations.getOrDefault(conversationId, List.of());
        }

        @Override
        public List<AgentTraceRecord> recent(String conversationId) {
            return recent.getOrDefault(conversationId, List.of());
        }

        @Override
        public Optional<AgentTraceRecord> byTraceId(String traceId) {
            if (failByTraceId) {
                throw new IllegalArgumentException(SENSITIVE_STORE_ERROR);
            }
            lastTraceId = Optional.of(traceId);
            return Optional.ofNullable(byTraceId.get(traceId));
        }
    }
}
