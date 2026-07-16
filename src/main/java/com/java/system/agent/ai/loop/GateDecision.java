package com.java.system.agent.ai.loop;

import org.springframework.util.Assert;

import java.util.Objects;

public record GateDecision(
        String gateName,
        boolean accepted,
        String critique,
        long durationMillis) {

    public GateDecision {
        Assert.hasText(gateName, "gateName must not be blank");
        critique = Objects.toString(critique, "");
        Assert.isTrue(durationMillis >= 0, "durationMillis must not be negative");
    }
}
