package com.java.system.agent.runtime.application;

public enum PlanningStatus {
    PLANNED,
    PREREQUISITE_MISSING,
    CAPABILITY_MISSING,
    AMBIGUOUS_CAPABILITY,
    BUDGET_EXHAUSTED
}
