package com.java.system.agent.ai.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "slack.app-token=",
        "slack.bot-token=xoxb-test",
        "slack.signing-secret=test",
        "spring.ai.openai.api-key=test-openai-key"
})
class TracePersistenceDisabledContextIntegrationTest {

    @Autowired
    private AgentTraceStore traceStore;

    @Test
    void defaultContextWiresInMemoryStore() {
        assertThat(traceStore).isInstanceOf(InMemoryAgentTraceStore.class);
    }
}
