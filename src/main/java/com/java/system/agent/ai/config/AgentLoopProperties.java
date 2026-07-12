package com.java.system.agent.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.loop")
public record AgentLoopProperties(
        @DefaultValue Analyst analyst,
        @DefaultValue Translator translator,
        @DefaultValue Trace trace,
        @DefaultValue RateLimit rateLimit) {

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

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") int requestsPerMinute,
            @DefaultValue("1000000") int tokensPerMinute,
            @DefaultValue("1500") int requestsPerDay,
            @DefaultValue("300000") long maxWaitMillis) {
    }
}
