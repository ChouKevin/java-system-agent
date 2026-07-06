package com.java.system.agent.ai.loop;

@FunctionalInterface
public interface StepExecutor {

    StepOutcome step(LoopState state);

    default Candidate forceAnswer(LoopState state) {
        throw new UnsupportedOperationException("forceAnswer not supported");
    }
}
