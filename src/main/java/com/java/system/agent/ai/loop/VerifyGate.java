package com.java.system.agent.ai.loop;

@FunctionalInterface
public interface VerifyGate {

    Verdict verify(Candidate candidate, LoopState state);
}
