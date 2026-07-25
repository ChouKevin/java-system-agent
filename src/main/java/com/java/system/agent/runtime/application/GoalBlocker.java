package com.java.system.agent.runtime.application;

public enum GoalBlocker {
    CAPABILITY_MISSING,
    PREREQUISITE_MISSING,
    BUDGET_EXHAUSTED,
    NO_PROGRESS,
    INVARIANT_FAILURE,
    CANCELLED
}
