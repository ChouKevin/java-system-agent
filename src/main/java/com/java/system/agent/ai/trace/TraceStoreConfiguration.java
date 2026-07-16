package com.java.system.agent.ai.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.config.AgentLoopProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
@EnableConfigurationProperties(TracePersistenceProperties.class)
public class TraceStoreConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "agent.trace",
            name = "persistence-enabled",
            havingValue = "false",
            matchIfMissing = true)
    AgentTraceStore inMemoryAgentTraceStore(AgentLoopProperties properties) {
        return new InMemoryAgentTraceStore(
                properties.trace().retain(),
                properties.trace().maxConversations());
    }

    @Bean
    @DependsOnDatabaseInitialization
    @ConditionalOnProperty(
            prefix = "agent.trace",
            name = "persistence-enabled",
            havingValue = "true")
    TracePartitionManager tracePartitionManager(JdbcClient jdbcClient) {
        return new TracePartitionManager(jdbcClient);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agent.trace",
            name = "persistence-enabled",
            havingValue = "true")
    AgentTraceStore postgresAgentTraceStore(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TracePartitionManager partitionManager) {
        return new PostgresAgentTraceStore(jdbcClient, objectMapper, partitionManager);
    }
}
