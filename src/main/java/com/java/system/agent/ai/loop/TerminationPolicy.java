package com.java.system.agent.ai.loop;

@FunctionalInterface
public interface TerminationPolicy {

    LoopDecision decide(LoopState state);
}
