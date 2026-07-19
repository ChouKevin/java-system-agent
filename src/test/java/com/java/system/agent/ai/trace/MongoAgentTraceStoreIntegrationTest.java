package com.java.system.agent.ai.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.StepMetrics;
import com.java.system.agent.ai.loop.TerminationReason;
import com.java.system.agent.ai.loop.Verdict;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MongoAgentTraceStoreIntegrationTest {

    private static final String FIRST_TRACE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND_TRACE_ID = "00000000-0000-0000-0000-000000000002";
    private static final String THIRD_TRACE_ID = "00000000-0000-0000-0000-000000000003";
    private static final String FOURTH_TRACE_ID = "00000000-0000-0000-0000-000000000004";
    private static final Instant BASE_TIME = Instant.parse("2026-07-15T10:00:00Z");
    private static final int RECENT_LIMIT = 2;

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    private static MongoTemplate mongoTemplate;
    private static ObjectMapper objectMapper;

    private MongoAgentTraceStore store;

    @BeforeAll
    static void setUpTemplate() {
        MongoClient mongoClient = MongoClients.create(MONGO.getConnectionString());
        mongoTemplate = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(mongoClient, "java_system_agent"));
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @BeforeEach
    void setUp() {
        mongoTemplate.getCollection(MongoAgentTraceStore.COLLECTION).drop();
        store = new MongoAgentTraceStore(mongoTemplate, objectMapper, RECENT_LIMIT);
    }

    @Test
    void saveThenByTraceIdRoundTripsAllFields() {
        AgentTraceRecord saved = trace(
                FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first");
        store.save(saved);

        Optional<AgentTraceRecord> loaded = store.byTraceId(FIRST_TRACE_ID);

        assertThat(loaded).contains(saved);
    }

    @Test
    void savePersistsPayloadVersion() {
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first"));

        Document document = mongoTemplate.getCollection(MongoAgentTraceStore.COLLECTION)
                .find(new Document("_id", FIRST_TRACE_ID))
                .first();

        assertThat(document).isNotNull();
        assertThat(document.getInteger("payloadVersion"))
                .isEqualTo(MongoAgentTraceStore.PAYLOAD_VERSION);
    }

    @Test
    void saveRejectsDuplicateTraceId() {
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first"));

        assertThatThrownBy(() -> store.save(
                trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "dup")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void byTraceIdWithMalformedUuidReturnsEmpty() {
        assertThat(store.byTraceId("not-a-uuid")).isEmpty();
    }

    @Test
    void searchFiltersByUserAcceptedAndTimeRange() {
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first"));
        store.save(trace(SECOND_TRACE_ID, "thread-1", "U2", "Ev2", false,
                BASE_TIME.plusSeconds(60), "second"));
        store.save(trace(THIRD_TRACE_ID, "thread-2", "U1", "Ev3", true,
                BASE_TIME.plusSeconds(120), "third"));

        List<AgentTraceSummary> byUser = store.search(TraceSearchQuery.of(
                "U1", null, null, null, null, 10));
        List<AgentTraceSummary> byAccepted = store.search(TraceSearchQuery.of(
                null, null, Boolean.FALSE, null, null, 10));
        List<AgentTraceSummary> byRange = store.search(TraceSearchQuery.of(
                null, null, null,
                BASE_TIME.plusSeconds(30), BASE_TIME.plusSeconds(90), 10));

        assertThat(byUser).extracting(AgentTraceSummary::traceId)
                .containsExactly(THIRD_TRACE_ID, FIRST_TRACE_ID);
        assertThat(byAccepted).extracting(AgentTraceSummary::traceId)
                .containsExactly(SECOND_TRACE_ID);
        assertThat(byRange).extracting(AgentTraceSummary::traceId)
                .containsExactly(SECOND_TRACE_ID);
    }

    @Test
    void searchHonorsLimitInReverseChronologicalOrder() {
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first"));
        store.save(trace(SECOND_TRACE_ID, "thread-1", "U1", "Ev2", true,
                BASE_TIME.plusSeconds(60), "second"));
        store.save(trace(THIRD_TRACE_ID, "thread-1", "U1", "Ev3", true,
                BASE_TIME.plusSeconds(120), "third"));

        List<AgentTraceSummary> summaries = store.search(TraceSearchQuery.of(
                null, null, null, null, null, 2));

        assertThat(summaries).extracting(AgentTraceSummary::traceId)
                .containsExactly(THIRD_TRACE_ID, SECOND_TRACE_ID);
    }

    @Test
    void conversationReturnsChronologicalOrder() {
        store.save(trace(SECOND_TRACE_ID, "thread-1", "U1", "Ev2", false,
                BASE_TIME.plusSeconds(60), "second"));
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first"));
        store.save(trace(THIRD_TRACE_ID, "thread-2", "U1", "Ev3", true,
                BASE_TIME.plusSeconds(120), "third"));

        List<AgentTraceRecord> conversation = store.conversation("thread-1");

        assertThat(conversation).extracting(AgentTraceRecord::traceId)
                .containsExactly(FIRST_TRACE_ID, SECOND_TRACE_ID);
    }

    @Test
    void recentReturnsReverseChronologicalOrderCappedByLimit() {
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first"));
        store.save(trace(SECOND_TRACE_ID, "thread-1", "U1", "Ev2", true,
                BASE_TIME.plusSeconds(60), "second"));
        store.save(trace(THIRD_TRACE_ID, "thread-1", "U1", "Ev3", true,
                BASE_TIME.plusSeconds(120), "third"));
        store.save(trace(FOURTH_TRACE_ID, "thread-1", "U1", "Ev4", true,
                BASE_TIME.plusSeconds(180), "fourth"));

        List<AgentTraceRecord> recent = store.recent("thread-1");

        assertThat(recent).extracting(AgentTraceRecord::traceId)
                .containsExactly(FOURTH_TRACE_ID, THIRD_TRACE_ID);
    }

    @Test
    void payloadWithUnknownFieldsStillDeserializes() {
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "Ev1", true, BASE_TIME, "first"));
        mongoTemplate.getCollection(MongoAgentTraceStore.COLLECTION).updateOne(
                new Document("_id", FIRST_TRACE_ID),
                new Document("$set", new Document("payload.futureField", "future value")));

        Optional<AgentTraceRecord> loaded = store.byTraceId(FIRST_TRACE_ID);

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().payload().loopTrace().finalAnswer())
                .isEqualTo("first final answer");
    }

    @Test
    void ensureIndexesCreatesAllIndexesIdempotently() {
        MongoTraceIndexInitializer initializer = new MongoTraceIndexInitializer(mongoTemplate);

        initializer.ensureIndexes();
        initializer.ensureIndexes();

        List<IndexInfo> indexes = mongoTemplate
                .indexOps(MongoAgentTraceStore.COLLECTION)
                .getIndexInfo();
        assertThat(indexes).hasSize(6);
    }

    private static AgentTraceRecord trace(
            String traceId,
            String conversationId,
            String userId,
            String eventId,
            boolean accepted,
            Instant createdAt,
            String marker) {
        TerminationReason terminationReason = accepted
                ? TerminationReason.ACCEPTED
                : TerminationReason.MAX_TURNS;
        LoopTrace childTrace = new LoopTrace(
                traceId + "-child",
                "translator",
                marker + " child answer",
                true,
                List.of(),
                List.of(),
                TerminationReason.ACCEPTED);
        LoopStep loopStep = new LoopStep(
                0,
                marker + " loop step",
                List.of("documentTools"),
                accepted ? Verdict.accept() : Verdict.revise(marker + " revise"),
                List.of(childTrace),
                new StepMetrics(25, 10, 5),
                marker + " candidate");
        LoopTrace loopTrace = new LoopTrace(
                traceId,
                "analyst",
                marker + " final answer",
                accepted,
                List.of(loopStep),
                List.of(),
                terminationReason);
        AgentTracePayload payload = new AgentTracePayload(
                List.of(
                        new MemoryMessageSnapshot("USER", marker + " memory question"),
                        new MemoryMessageSnapshot("ASSISTANT", marker + " memory answer")),
                loopTrace);
        return new AgentTraceRecord(
                createdAt,
                traceId,
                createdAt.plusMillis(25),
                conversationId,
                userId,
                "T-" + marker,
                "C-" + marker,
                eventId,
                marker + " query",
                marker + " candidate",
                marker + " slack response",
                accepted,
                terminationReason,
                1,
                accepted ? 0 : 1,
                10,
                5,
                25,
                payload);
    }
}
