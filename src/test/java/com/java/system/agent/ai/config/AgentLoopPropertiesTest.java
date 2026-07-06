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
    }

    @Test
    void bindsOverrides_fromPropertySource() {
        AgentLoopProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("agent.loop.analyst.max-turns", "5")))
                .bindOrCreate("agent.loop", Bindable.of(AgentLoopProperties.class));

        assertThat(properties.analyst().maxTurns()).isEqualTo(5);
    }
}
