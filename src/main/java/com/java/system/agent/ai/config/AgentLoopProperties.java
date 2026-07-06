package com.java.system.agent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.loop")
public record AgentLoopProperties(
        @DefaultValue Analyst analyst,
        @DefaultValue Translator translator,
        @DefaultValue Trace trace) {

    public record Analyst(
            @DefaultValue("12") int maxTurns,
            @DefaultValue("120000") long maxWallMs,
            @DefaultValue("2") int noProgressLimit) {
    }

    public record Translator(
            @DefaultValue("6") int maxTurns,
            @DefaultValue("60000") long maxWallMs) {
    }

    public record Trace(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") int retain,
            @DefaultValue("200") int maxConversations) {
    }
}
