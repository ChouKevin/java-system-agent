package com.java.system.agent.ai.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("uat")
@SpringBootTest(properties = {
        "slack.app-token=",
        "slack.bot-token=xoxb-test",
        "slack.signing-secret=test",
        "spring.ai.google.genai.api-key=test-google-key",
        "spring.ai.openai.api-key=test-openai-key",
        "agent.trace.persistence-enabled=true"
})
class UatTracePersistenceContextIntegrationTest {

    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("java_system_agent")
                    .withUsername("agent")
                    .withPassword("agent");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AgentTraceStore traceStore;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void uatContext_migratesBeforePartitionStartupAndSelectsPostgresStore() {
        Integer successfulMigrationCount = jdbcClient.sql("""
                        SELECT count(*)
                        FROM observability.flyway_schema_history
                        WHERE success
                          AND version = '1'
                        """)
                .query(Integer.class)
                .single();
        List<String> partitionNames = jdbcClient.sql("""
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
        Map<String, AgentTraceStore> traceStores = applicationContext.getBeansOfType(AgentTraceStore.class);
        ZonedDateTime currentMonth = Instant.now().atZone(ZoneOffset.UTC);
        String currentPartition = partitionName(currentMonth);
        String nextPartition = partitionName(currentMonth.plusMonths(1));

        assertThat(successfulMigrationCount).isEqualTo(1);
        assertThat(applicationContext.getBean(TracePartitionManager.class)).isNotNull();
        assertThat(traceStore).isInstanceOf(PostgresAgentTraceStore.class);
        assertThat(traceStores).containsOnlyKeys("postgresAgentTraceStore");
        assertThat(partitionNames).contains(currentPartition, nextPartition);
    }

    private static String partitionName(ZonedDateTime target) {
        return "agent_trace_" + PARTITION_SUFFIX.format(target);
    }
}
