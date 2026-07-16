package com.java.system.agent.ai.loop;

@FunctionalInterface
public interface TerminationPolicy {

    TerminationDecision decide(LoopState state);
}
