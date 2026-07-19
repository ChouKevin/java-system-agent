package com.java.system.agent.ai.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

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

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                () -> MONGO.getConnectionString() + "/java_system_agent");
    }

    @Autowired
    private AgentTraceStore traceStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void persistenceEnabledContextWiresMongoStore() {
        assertThat(traceStore).isInstanceOf(MongoAgentTraceStore.class);
    }

    @Test
    void startupCreatesAgentTraceIndexes() {
        List<IndexInfo> indexes = mongoTemplate
                .indexOps(MongoAgentTraceStore.COLLECTION)
                .getIndexInfo();
        assertThat(indexes).hasSize(6);
    }
}
