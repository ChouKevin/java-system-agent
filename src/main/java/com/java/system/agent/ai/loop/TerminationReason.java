package com.java.system.agent.ai.loop;

public enum TerminationReason {
    ACCEPTED,
    NO_PROGRESS,
    MAX_TURNS,
    ANALYST_TIMEOUT,
    TRANSLATOR_TIMEOUT,
    OVERALL_TIMEOUT,
    STEP_ERROR,
    CANCELLED
}
