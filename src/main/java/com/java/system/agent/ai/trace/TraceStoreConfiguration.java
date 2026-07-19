package com.java.system.agent.ai.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.config.AgentLoopProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

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
    @ConditionalOnProperty(
            prefix = "agent.trace",
            name = "persistence-enabled",
            havingValue = "true")
    MongoTraceIndexInitializer mongoTraceIndexInitializer(MongoTemplate mongoTemplate) {
        return new MongoTraceIndexInitializer(mongoTemplate);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agent.trace",
            name = "persistence-enabled",
            havingValue = "true")
    AgentTraceStore mongoAgentTraceStore(
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            AgentLoopProperties properties) {
        return new MongoAgentTraceStore(
                mongoTemplate, objectMapper, properties.trace().retain());
    }
}
