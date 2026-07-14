package com.java.system.agent.ai.loop;

@FunctionalInterface
public interface TerminalAnswerPolicy {

    Candidate apply(Candidate candidate);

    static TerminalAnswerPolicy identity() {
        return candidate -> candidate;
    }
}
