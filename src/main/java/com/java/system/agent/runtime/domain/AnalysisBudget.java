package com.java.system.agent.runtime.domain;

public record AnalysisBudget(
        int maxSteps,
        int usedSteps,
        int maxSemanticCalls,
        int usedSemanticCalls) {

    public AnalysisBudget {
        if (maxSteps < 1 || maxSemanticCalls < 1) {
            throw new IllegalArgumentException("analysis budget limits must be positive");
        }
        if (usedSteps < 0 || usedSteps > maxSteps) {
            throw new IllegalArgumentException("used steps must be within the configured budget");
        }
        if (usedSemanticCalls < 0 || usedSemanticCalls > maxSemanticCalls) {
            throw new IllegalArgumentException("used semantic calls must be within the configured budget");
        }
    }

    public static AnalysisBudget of(int maxSteps, int maxSemanticCalls) {
        return new AnalysisBudget(maxSteps, 0, maxSemanticCalls, 0);
    }

    public AnalysisBudget consumeStep() {
        if (!hasStepRemaining()) {
            throw new IllegalArgumentException("analysis step budget is exhausted");
        }
        return new AnalysisBudget(maxSteps, usedSteps + 1, maxSemanticCalls, usedSemanticCalls);
    }

    public AnalysisBudget consumeSemanticCall() {
        if (!hasSemanticCallRemaining()) {
            throw new IllegalArgumentException("semantic call budget is exhausted");
        }
        return new AnalysisBudget(maxSteps, usedSteps, maxSemanticCalls, usedSemanticCalls + 1);
    }

    public boolean hasStepRemaining() {
        return usedSteps < maxSteps;
    }

    public boolean hasSemanticCallRemaining() {
        return usedSemanticCalls < maxSemanticCalls;
    }
}
