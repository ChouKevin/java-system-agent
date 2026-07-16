package com.java.system.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "agent.trace.persistence-enabled=false",
        "slack.app-token=",
        "slack.bot-token=xoxb-test",
        "slack.signing-secret=test",
        "spring.ai.openai.api-key=test"
})
class ApplicationStartupTest {

    @Test
    void should_start_application_context_when_slack_socket_mode_is_not_configured() {
    }
}
