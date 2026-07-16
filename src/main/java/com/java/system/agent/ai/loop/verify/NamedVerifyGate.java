package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.VerifyGate;
import org.springframework.util.Assert;

import java.util.Objects;

public record NamedVerifyGate(String name, VerifyGate delegate) {

    public NamedVerifyGate {
        Assert.hasText(name, "name must not be blank");
        delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }
}
