package com.java.system.agent.ai.loop;

import org.springframework.util.Assert;

import java.util.Objects;
import java.util.Optional;

public record TerminationDecision(
        LoopDecision action,
        Optional<TerminationReason> reason) {

    public TerminationDecision {
        action = Objects.requireNonNull(action, "action must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public static TerminationDecision continueLoop() {
        return new TerminationDecision(LoopDecision.CONTINUE, Optional.empty());
    }

    public static TerminationDecision terminate(LoopDecision action, TerminationReason reason) {
        Assert.isTrue(!LoopDecision.CONTINUE.equals(action), "terminal action must not be CONTINUE");
        return new TerminationDecision(action, Optional.of(reason));
    }
}
