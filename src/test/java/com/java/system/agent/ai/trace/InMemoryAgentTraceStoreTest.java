package com.java.system.agent.ai.trace;

import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.TerminationReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryAgentTraceStoreTest {

    private static final Instant BASE_TIME = Instant.parse("2026-07-15T00:00:00Z");

    @Test
    void conversation_returnsOldestTraceFirstWhenSavedOutOfOrder() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("new", "thread-1", "U1", "E2", true, BASE_TIME.plusSeconds(1)));
        store.save(trace("old", "thread-1", "U1", "E1", true, BASE_TIME));

        assertThat(store.conversation("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly("old", "new");
    }

    @Test
    void recent_returnsNewestTraceFirstWhenSavedOutOfOrder() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("new", "thread-1", "U1", "E2", true, BASE_TIME.plusSeconds(1)));
        store.save(trace("old", "thread-1", "U1", "E1", true, BASE_TIME));

        assertThat(store.recent("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly("new", "old");
    }

    @Test
    void search_returnsAllConversationsNewestFirst() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("middle", "thread-1", "U1", "E1", true, BASE_TIME.plusSeconds(1)));
        store.save(trace("old", "thread-2", "U2", "E2", false, BASE_TIME));
        store.save(trace("new", "thread-1", "U1", "E3", true, BASE_TIME.plusSeconds(2)));

        assertThat(store.search(query(10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly("new", "middle", "old");
    }

    @Test
    void search_filtersByUserId() {
        InMemoryAgentTraceStore store = populatedStore();

        TraceSearchQuery query = new TraceSearchQuery(
                Optional.of("U1"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 50);

        assertThat(store.search(query))
                .extracting(AgentTraceSummary::userId)
                .containsOnly("U1");
    }

    @Test
    void search_filtersByEventId() {
        InMemoryAgentTraceStore store = populatedStore();

        TraceSearchQuery query = new TraceSearchQuery(
                Optional.empty(), Optional.of("E2"), Optional.empty(),
                Optional.empty(), Optional.empty(), 50);

        assertThat(store.search(query))
                .extracting(AgentTraceSummary::eventId)
                .containsExactly("E2");
    }

    @Test
    void search_filtersByAcceptedFlag() {
        InMemoryAgentTraceStore store = populatedStore();

        TraceSearchQuery query = new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.of(false),
                Optional.empty(), Optional.empty(), 50);

        assertThat(store.search(query))
                .extracting(AgentTraceSummary::accepted)
                .containsOnly(false);
    }

    @Test
    void search_includesFromBoundary() {
        InMemoryAgentTraceStore store = populatedStore();

        TraceSearchQuery query = new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BASE_TIME.plusSeconds(1)), Optional.empty(), 50);

        assertThat(store.search(query))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly("trace-3", "trace-2");
    }

    @Test
    void search_excludesToBoundary() {
        InMemoryAgentTraceStore store = populatedStore();

        TraceSearchQuery query = new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(BASE_TIME.plusSeconds(2)), 50);

        assertThat(store.search(query))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly("trace-2", "trace-1");
    }

    @Test
    void search_appliesLimitAfterSortingAndFiltering() {
        InMemoryAgentTraceStore store = populatedStore();

        assertThat(store.search(query(2)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly("trace-3", "trace-2");
    }

    @Test
    void search_breaksCreatedAtTiesByTraceIdDescendingBeforeApplyingLimit() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("a", "thread-1", "U1", "E1", true, BASE_TIME));
        store.save(trace("b", "thread-2", "U2", "E2", true, BASE_TIME));
        store.save(trace("c", "thread-3", "U3", "E3", true, BASE_TIME));

        assertThat(store.search(query(2)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly("c", "b");
    }

    @Test
    void summary_excludesCandidateSlackResponseAndPayload() {
        List<String> componentNames = Arrays.stream(AgentTraceSummary.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(componentNames)
                .doesNotContain("finalCandidate", "slackResponse", "payload");
    }

    @Test
    void byTraceId_returnsSavedTrace() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        AgentTraceRecord trace = trace("trace-1", "thread-1", "U1", "E1", true, BASE_TIME);
        store.save(trace);

        assertThat(store.byTraceId("trace-1")).containsSame(trace);
        assertThat(store.byTraceId("missing")).isEmpty();
    }

    @Test
    void save_evictsOldestTraceBeyondPerConversationRetain() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(2, 3);
        store.save(trace("old", "thread-1", "U1", "E1", true, BASE_TIME));
        store.save(trace("middle", "thread-1", "U1", "E2", true, BASE_TIME.plusSeconds(1)));
        store.save(trace("new", "thread-1", "U1", "E3", true, BASE_TIME.plusSeconds(2)));

        assertThat(store.conversation("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly("middle", "new");
        assertThat(store.byTraceId("old")).isEmpty();
    }

    @Test
    void save_immediatelyEvictsLateOlderTraceBeyondPerConversationRetain() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(2, 3);
        store.save(trace("middle", "thread-1", "U1", "E1", true, BASE_TIME.plusSeconds(1)));
        store.save(trace("new", "thread-1", "U1", "E2", true, BASE_TIME.plusSeconds(2)));
        store.save(trace("late-old", "thread-1", "U1", "E3", true, BASE_TIME));

        assertThat(store.conversation("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly("middle", "new");
        assertThat(store.byTraceId("late-old")).isEmpty();
    }

    @Test
    void conversation_breaksCreatedAtTiesByTraceIdAscending() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("trace-b", "thread-1", "U1", "E1", true, BASE_TIME));
        store.save(trace("trace-a", "thread-1", "U1", "E2", true, BASE_TIME));

        assertThat(store.conversation("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly("trace-a", "trace-b");
    }

    @Test
    void recent_isExactReverseWhenCreatedAtValuesAreEqual() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("trace-b", "thread-1", "U1", "E1", true, BASE_TIME));
        store.save(trace("trace-a", "thread-1", "U1", "E2", true, BASE_TIME));

        assertThat(store.recent("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly("trace-b", "trace-a");
    }

    @Test
    void save_evictsLeastRecentlyWrittenConversationBeyondGlobalLimit() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(2, 2);
        store.save(trace("thread-1-old", "thread-1", "U1", "E1", true, BASE_TIME));
        store.save(trace("thread-2", "thread-2", "U2", "E2", true, BASE_TIME.plusSeconds(1)));
        store.save(trace("thread-1-new", "thread-1", "U1", "E3", true, BASE_TIME.plusSeconds(2)));
        store.save(trace("thread-3", "thread-3", "U3", "E4", true, BASE_TIME.plusSeconds(3)));

        assertThat(store.conversation("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly("thread-1-old", "thread-1-new");
        assertThat(store.conversation("thread-2")).isEmpty();
        assertThat(store.byTraceId("thread-2")).isEmpty();
    }

    @Test
    void save_replacesDuplicateTraceIdWithoutLeavingStaleConversationEntry() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("duplicate", "thread-1", "U1", "E1", false, BASE_TIME));
        AgentTraceRecord replacement = trace(
                "duplicate", "thread-2", "U2", "E2", true, BASE_TIME.plusSeconds(1));
        store.save(replacement);

        assertThat(store.conversation("thread-1")).isEmpty();
        assertThat(store.conversation("thread-2")).containsExactly(replacement);
        assertThat(store.byTraceId("duplicate")).containsSame(replacement);
        assertThat(store.search(query(10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly("duplicate");
    }

    @Test
    void save_replacesDuplicateTraceIdWithinSameConversation() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("duplicate", "thread-1", "U1", "E1", false, BASE_TIME));
        AgentTraceRecord replacement = trace(
                "duplicate", "thread-1", "U2", "E2", true, BASE_TIME.plusSeconds(1));

        store.save(replacement);

        assertThat(store.conversation("thread-1")).containsExactly(replacement);
        assertThat(store.byTraceId("duplicate")).containsSame(replacement);
        assertThat(store.search(query(10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly("duplicate");
    }

    @Test
    void query_rejectsNullOptionalFields() {
        assertThatThrownBy(() -> new TraceSearchQuery(
                null, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userId must not be null");
    }

    @Test
    void query_rejectsLimitBelowOne() {
        assertThatThrownBy(() -> query(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");
    }

    @Test
    void query_rejectsLimitAboveOneHundred() {
        assertThatThrownBy(() -> query(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");
    }

    @Test
    void query_rejectsEqualTimeRange() {
        assertThatThrownBy(() -> new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BASE_TIME), Optional.of(BASE_TIME), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must be before to");
    }

    @Test
    void query_rejectsDecreasingTimeRange() {
        assertThatThrownBy(() -> new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BASE_TIME.plusSeconds(1)), Optional.of(BASE_TIME), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must be before to");
    }

    @Test
    void queryFactory_ignoresBlankTextFilters() {
        TraceSearchQuery query = TraceSearchQuery.of(" ", "", null, null, null, 10);

        assertThat(query.userId()).isEmpty();
        assertThat(query.eventId()).isEmpty();
        assertThat(query.accepted()).isEmpty();
        assertThat(query.from()).isEmpty();
        assertThat(query.to()).isEmpty();
    }

    private static InMemoryAgentTraceStore populatedStore() {
        InMemoryAgentTraceStore store = new InMemoryAgentTraceStore(3, 3);
        store.save(trace("trace-1", "thread-1", "U1", "E1", true, BASE_TIME));
        store.save(trace("trace-2", "thread-2", "U2", "E2", false, BASE_TIME.plusSeconds(1)));
        store.save(trace("trace-3", "thread-1", "U1", "E3", false, BASE_TIME.plusSeconds(2)));
        return store;
    }

    private static TraceSearchQuery query(int limit) {
        return new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), limit);
    }

    private static AgentTraceRecord trace(
            String traceId,
            String conversationId,
            String userId,
            String eventId,
            boolean accepted,
            Instant createdAt) {
        TerminationReason terminationReason = accepted
                ? TerminationReason.ACCEPTED
                : TerminationReason.MAX_TURNS;
        LoopTrace loopTrace = new LoopTrace(
                traceId, "analyst", "sensitive candidate", accepted,
                List.of(), List.of(), terminationReason);
        AgentTracePayload payload = new AgentTracePayload(
                List.of(new MemoryMessageSnapshot("USER", "sensitive memory")), loopTrace);
        return new AgentTraceRecord(
                createdAt,
                traceId,
                createdAt.plusMillis(25),
                conversationId,
                userId,
                "T1",
                "C1",
                eventId,
                "query-" + traceId,
                "candidate-" + traceId,
                "slack-" + traceId,
                accepted,
                terminationReason,
                2,
                accepted ? 0 : 2,
                10,
                5,
                25,
                payload);
    }
}
