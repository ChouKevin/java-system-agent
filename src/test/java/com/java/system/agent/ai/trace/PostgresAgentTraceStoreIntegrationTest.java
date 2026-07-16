package com.java.system.agent.ai.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.StepMetrics;
import com.java.system.agent.ai.loop.TerminationReason;
import com.java.system.agent.ai.loop.Verdict;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresAgentTraceStoreIntegrationTest {

    private static final String FIRST_TRACE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND_TRACE_ID = "00000000-0000-0000-0000-000000000002";
    private static final String THIRD_TRACE_ID = "00000000-0000-0000-0000-000000000003";
    private static final String FOURTH_TRACE_ID = "00000000-0000-0000-0000-000000000004";
    private static final Instant JULY = Instant.parse("2026-07-15T10:00:00Z");
    private static final Instant AUGUST = Instant.parse("2026-08-15T10:00:00Z");
    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("java_system_agent")
                    .withUsername("agent")
                    .withPassword("agent");

    private static JdbcClient jdbcClient;
    private static ObjectMapper objectMapper;
    private static TracePartitionManager partitionManager;

    private PostgresAgentTraceStore store;

    @BeforeAll
    static void migrateDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .createSchemas(true)
                .schemas("observability")
                .defaultSchema("observability")
                .load()
                .migrate();
        jdbcClient = JdbcClient.create(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        partitionManager = new TracePartitionManager(jdbcClient);
    }

    @BeforeEach
    void resetRows() {
        jdbcClient.sql("TRUNCATE TABLE observability.agent_trace").update();
        store = new PostgresAgentTraceStore(jdbcClient, objectMapper, partitionManager);
    }

    @Test
    void migration_createsPartitionedTableFunctionAndDeterministicIndexes() {
        Optional<String> schema = jdbcClient.sql("""
                        SELECT schemaname
                        FROM pg_tables
                        WHERE schemaname = 'observability'
                          AND tablename = 'agent_trace'
                        """)
                .query(String.class)
                .optional();
        Integer partitionedTableCount = jdbcClient.sql("""
                        SELECT count(*)
                        FROM pg_partitioned_table partitioned
                        JOIN pg_class table_class ON table_class.oid = partitioned.partrelid
                        JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                        WHERE namespace.nspname = 'observability'
                          AND table_class.relname = 'agent_trace'
                        """)
                .query(Integer.class)
                .single();
        Integer functionCount = jdbcClient.sql("""
                        SELECT count(*)
                        FROM pg_proc function
                        JOIN pg_namespace namespace ON namespace.oid = function.pronamespace
                        WHERE namespace.nspname = 'observability'
                          AND function.proname = 'ensure_agent_trace_partition'
                        """)
                .query(Integer.class)
                .single();

        partitionManager.ensurePartition(JULY);
        List<String> indexDefinitions = jdbcClient.sql("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'observability'
                          AND tablename = 'agent_trace_2026_07'
                        """)
                .query(String.class)
                .list();

        assertThat(schema).contains("observability");
        assertThat(partitionedTableCount).isEqualTo(1);
        assertThat(functionCount).isEqualTo(1);
        assertThat(indexDefinitions)
                .anyMatch(definition -> definition.contains("trace_id, created_at"))
                .anyMatch(definition -> definition.contains("conversation_id, created_at, trace_id"))
                .anyMatch(definition -> definition.contains("user_id, created_at, trace_id"))
                .anyMatch(definition -> definition.contains("event_id, created_at, trace_id"))
                .anyMatch(definition -> definition.contains("accepted, created_at, trace_id"));
    }

    @Test
    void partitionManager_declaresDatabaseInitializationDependency() {
        assertThat(TracePartitionManager.class)
                .hasAnnotation(DependsOnDatabaseInitialization.class);
    }

    @Test
    void ensurePartition_isIdempotentAndCreatesTwoRequestedMonths() {
        partitionManager.ensurePartition(JULY);
        partitionManager.ensurePartition(JULY);
        partitionManager.ensurePartition(AUGUST);

        assertThat(partitionNames())
                .contains("agent_trace_2026_07", "agent_trace_2026_08");
    }

    @Test
    void ensurePartition_usesExactUtcMonthBoundsAcrossDstTransition() {
        Instant march = Instant.parse("2026-03-15T10:00:00Z");
        Instant finalSecond = Instant.parse("2026-03-31T23:59:59Z");
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("""
                        DROP TABLE IF EXISTS observability.agent_trace_2026_03
                        """)
                .update();

        transactionTemplate.executeWithoutResult(status -> {
            JdbcClient sessionClient = JdbcClient.create(dataSource);
            TracePartitionManager sessionPartitionManager = new TracePartitionManager(sessionClient);
            sessionClient.sql("""
                            SET LOCAL TIME ZONE 'America/New_York'
                            """)
                    .update();
            String sessionTimeZone = sessionClient.sql("""
                            SHOW TIME ZONE
                            """)
                    .query(String.class)
                    .single();

            sessionPartitionManager.ensurePartition(march);

            assertThat(sessionTimeZone).isEqualTo("America/New_York");
        });

        String partitionBound = transactionTemplate.execute(status -> {
            JdbcClient sessionClient = JdbcClient.create(dataSource);
            sessionClient.sql("""
                            SET LOCAL TIME ZONE 'UTC'
                            """)
                    .update();
            return sessionClient.sql("""
                            SELECT pg_get_expr(child.relpartbound, child.oid)
                            FROM pg_class child
                            JOIN pg_namespace namespace ON namespace.oid = child.relnamespace
                            WHERE namespace.nspname = 'observability'
                              AND child.relname = 'agent_trace_2026_03'
                            """)
                    .query(String.class)
                    .single();
        });
        store.save(trace(
                FIRST_TRACE_ID,
                "thread-dst",
                "U-DST",
                "E-DST",
                true,
                finalSecond,
                "dst"));
        Integer routedRowCount = jdbcClient.sql("""
                        SELECT count(*)
                        FROM observability.agent_trace_2026_03
                        WHERE trace_id = CAST(:trace_id AS UUID)
                        """)
                .param("trace_id", FIRST_TRACE_ID)
                .query(Integer.class)
                .single();

        assertThat(partitionBound).isEqualTo(
                "FOR VALUES FROM ('2026-03-01 00:00:00+00') "
                        + "TO ('2026-04-01 00:00:00+00')");
        assertThat(routedRowCount).isEqualTo(1);
    }

    @Test
    void ensurePartition_serializesConcurrentCreationForTheSameMonth()
            throws InterruptedException, ExecutionException {
        Instant target = Instant.parse("2031-09-15T10:00:00Z");
        String partitionName = "agent_trace_2031_09";
        int taskCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int taskIndex = 0; taskIndex < taskCount; taskIndex++) {
            tasks.add(() -> {
                partitionManager.ensurePartition(target);
                return true;
            });
        }

        try {
            List<Future<Boolean>> futures = executor.invokeAll(tasks);
            for (Future<Boolean> future : futures) {
                assertThat(future.get()).isTrue();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(partitionNames())
                .filteredOn(partitionName::equals)
                .containsExactly(partitionName);
    }

    @Test
    void initializePartitions_recreatesCurrentAndNextMonthWithoutDeletingOldPartitions() {
        Instant oldMonth = Instant.parse("2020-01-15T10:00:00Z");
        Instant current = Instant.now();
        Instant nextMonth = current.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        String currentPartition = partitionName(current);
        String nextPartition = partitionName(nextMonth);
        partitionManager.ensurePartition(oldMonth);
        jdbcClient.sql("""
                        DROP TABLE IF EXISTS observability.%s
                        """.formatted(currentPartition))
                .update();
        jdbcClient.sql("""
                        DROP TABLE IF EXISTS observability.%s
                        """.formatted(nextPartition))
                .update();

        partitionManager.initializePartitions();

        assertThat(partitionNames())
                .contains("agent_trace_2020_01", currentPartition, nextPartition);
    }

    @Test
    void save_roundTripsEveryColumnNestedPayloadTwoMonthsAndSurvivesRepositoryRecreation() {
        AgentTraceRecord first = trace(
                FIRST_TRACE_ID, "thread-1", "U1", "E1", true, JULY, "first");
        AgentTraceRecord second = trace(
                SECOND_TRACE_ID, "thread-1", "U2", "E2", false, AUGUST, "second");

        store.save(second);
        store.save(first);
        partitionManager.ensurePartition(JULY);

        PostgresAgentTraceStore recreatedStore =
                new PostgresAgentTraceStore(jdbcClient, objectMapper, partitionManager);

        assertThat(recreatedStore.byTraceId(FIRST_TRACE_ID)).contains(first);
        assertThat(recreatedStore.byTraceId(SECOND_TRACE_ID)).contains(second);
        assertThat(recreatedStore.conversation("thread-1"))
                .containsExactly(first, second);
        assertThat(partitionNames())
                .contains("agent_trace_2026_07", "agent_trace_2026_08");
    }

    @Test
    void save_createsTheTargetMonthPartitionImmediatelyBeforeInsert() {
        Instant futureMonth = Instant.parse("2027-03-15T10:00:00Z");

        store.save(trace(
                FIRST_TRACE_ID, "thread-future", "U1", "E1", true, futureMonth, "future"));

        assertThat(partitionNames()).contains("agent_trace_2027_03");
        assertThat(store.byTraceId(FIRST_TRACE_ID)).isPresent();
    }

    @Test
    void byTraceId_returnsLatestOccurrenceOfTheSameUuidAcrossPartitions() {
        AgentTraceRecord older = trace(
                FIRST_TRACE_ID, "thread-old", "U1", "E1", true, JULY, "older");
        AgentTraceRecord newer = trace(
                FIRST_TRACE_ID, "thread-new", "U1", "E2", false, AUGUST, "newer");
        store.save(newer);
        store.save(older);

        assertThat(store.byTraceId(FIRST_TRACE_ID)).contains(newer);
    }

    @Test
    void conversationRecentAndSearch_useExactOppositeTraceIdTieBreakers() {
        AgentTraceRecord first = trace(
                FIRST_TRACE_ID, "thread-1", "U1", "E1", true, JULY, "first");
        AgentTraceRecord second = trace(
                SECOND_TRACE_ID, "thread-1", "U1", "E2", true, JULY, "second");
        store.save(second);
        store.save(first);

        assertThat(store.conversation("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly(FIRST_TRACE_ID, SECOND_TRACE_ID);
        assertThat(store.recent("thread-1"))
                .extracting(AgentTraceRecord::traceId)
                .containsExactly(SECOND_TRACE_ID, FIRST_TRACE_ID);
        assertThat(store.search(query(1)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly(SECOND_TRACE_ID);
    }

    @Test
    void search_appliesEveryFilterInclusiveFromExclusiveToAndLimitAfterSorting() {
        Instant firstTime = JULY;
        Instant secondTime = JULY.plusSeconds(1);
        Instant thirdTime = JULY.plusSeconds(2);
        store.save(trace(FIRST_TRACE_ID, "thread-1", "U1", "E1", true, firstTime, "first"));
        store.save(trace(SECOND_TRACE_ID, "thread-2", "U2", "E2", false, secondTime, "second"));
        store.save(trace(THIRD_TRACE_ID, "thread-3", "U1", "E3", false, thirdTime, "third"));
        store.save(trace(FOURTH_TRACE_ID, "thread-4", "U1", "E4", true, thirdTime, "fourth"));

        assertThat(store.search(new TraceSearchQuery(
                Optional.of("U1"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly(FOURTH_TRACE_ID, THIRD_TRACE_ID, FIRST_TRACE_ID);
        assertThat(store.search(new TraceSearchQuery(
                Optional.empty(), Optional.of("E2"), Optional.empty(),
                Optional.empty(), Optional.empty(), 10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly(SECOND_TRACE_ID);
        assertThat(store.search(new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.of(false),
                Optional.empty(), Optional.empty(), 10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly(THIRD_TRACE_ID, SECOND_TRACE_ID);
        assertThat(store.search(new TraceSearchQuery(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(secondTime), Optional.of(thirdTime), 10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly(SECOND_TRACE_ID);
        assertThat(store.search(new TraceSearchQuery(
                Optional.of("U1"), Optional.of("E4"), Optional.of(true),
                Optional.of(secondTime), Optional.of(thirdTime.plusSeconds(1)), 10)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly(FOURTH_TRACE_ID);
        assertThat(store.search(query(2)))
                .extracting(AgentTraceSummary::traceId)
                .containsExactly(FOURTH_TRACE_ID, THIRD_TRACE_ID);
    }

    @Test
    void byTraceId_returnsEmptyForMalformedAndUnknownUuidValues() {
        assertThat(store.byTraceId("not-a-uuid")).isEmpty();
        assertThat(store.byTraceId("00000000-0000-0000-0000-999999999999")).isEmpty();
    }

    private static List<String> partitionNames() {
        return jdbcClient.sql("""
                        SELECT child.relname
                        FROM pg_inherits inheritance
                        JOIN pg_class parent ON parent.oid = inheritance.inhparent
                        JOIN pg_class child ON child.oid = inheritance.inhrelid
                        JOIN pg_namespace namespace ON namespace.oid = child.relnamespace
                        WHERE namespace.nspname = 'observability'
                          AND parent.relname = 'agent_trace'
                        ORDER BY child.relname
                        """)
                .query(String.class)
                .list();
    }

    private static String partitionName(Instant target) {
        return "agent_trace_" + PARTITION_SUFFIX.format(target.atZone(ZoneOffset.UTC));
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
