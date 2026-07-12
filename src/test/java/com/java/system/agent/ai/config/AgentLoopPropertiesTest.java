package com.java.system.agent.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopPropertiesTest {

    @Test
    void bindsDefaults_whenNoKeysPresent() {
        AgentLoopProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("agent.loop", Bindable.of(AgentLoopProperties.class));

        assertThat(properties.analyst().maxTurns()).isEqualTo(12);
        assertThat(properties.analyst().maxWallMs()).isEqualTo(120_000L);
        assertThat(properties.analyst().noProgressLimit()).isEqualTo(2);
        assertThat(properties.translator().maxTurns()).isEqualTo(6);
        assertThat(properties.translator().maxWallMs()).isEqualTo(60_000L);
        assertThat(properties.trace().enabled()).isTrue();
        assertThat(properties.trace().retain()).isEqualTo(20);
        assertThat(properties.trace().maxConversations()).isEqualTo(200);
        assertThat(properties.rateLimit().enabled()).isTrue();
        assertThat(properties.rateLimit().requestsPerMinute()).isEqualTo(30);
        assertThat(properties.rateLimit().tokensPerMinute()).isEqualTo(1_000_000);
        assertThat(properties.rateLimit().requestsPerDay()).isEqualTo(1_500);
        assertThat(properties.rateLimit().maxWaitMillis()).isEqualTo(300_000L);
    }

    @Test
    void bindsOverrides_fromPropertySource() {
        AgentLoopProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of(
                        "agent.loop.analyst.max-turns", "5",
                        "agent.loop.rate-limit.requests-per-minute", "12",
                        "agent.loop.rate-limit.tokens-per-minute", "9000",
                        "agent.loop.rate-limit.requests-per-day", "300",
                        "agent.loop.rate-limit.max-wait-millis", "120000")))
                .bindOrCreate("agent.loop", Bindable.of(AgentLoopProperties.class));

        assertThat(properties.analyst().maxTurns()).isEqualTo(5);
        assertThat(properties.rateLimit().requestsPerMinute()).isEqualTo(12);
        assertThat(properties.rateLimit().tokensPerMinute()).isEqualTo(9_000);
        assertThat(properties.rateLimit().requestsPerDay()).isEqualTo(300);
        assertThat(properties.rateLimit().maxWaitMillis()).isEqualTo(120_000L);
    }
}
