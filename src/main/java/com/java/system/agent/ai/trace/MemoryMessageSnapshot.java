package com.java.system.agent.ai.trace;

import org.springframework.util.Assert;

import java.util.Objects;

public record MemoryMessageSnapshot(String role, String content) {

    public MemoryMessageSnapshot {
        Assert.hasText(role, "role must not be blank");
        content = Objects.toString(content, "");
    }
}
