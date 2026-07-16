package com.java.system.agent.ai.loop;

import org.springframework.util.Assert;

import java.util.Objects;

/** Safe, bounded metadata captured from a tool execution result. */
public record ToolResultObservation(
        String status,
        String failureCode,
        long durationMillis,
        int rawLength,
        String sha256,
        String summary) {

    public ToolResultObservation {
        Assert.hasText(status, "status must not be blank");
        failureCode = Objects.toString(failureCode, "");
        sha256 = Objects.toString(sha256, "");
        summary = Objects.toString(summary, "");
        Assert.isTrue(durationMillis >= 0, "durationMillis must not be negative");
        Assert.isTrue(rawLength >= 0, "rawLength must not be negative");
        Assert.isTrue(summary.length() <= 2_000, "summary exceeds 2000 characters");
    }

    public static ToolResultObservation pending() {
        return new ToolResultObservation("PENDING", "", 0L, 0, "", "");
    }

    public static ToolResultObservation failed(String failureCode, long durationMillis) {
        return new ToolResultObservation("FAILED", failureCode, durationMillis, 0, "", "");
    }
}
