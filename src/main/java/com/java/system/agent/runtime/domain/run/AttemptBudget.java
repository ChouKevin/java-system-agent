package com.java.system.agent.runtime.domain.run;

/**
 * 一次 Attempt 可消耗的步數與語意呼叫上限，以及目前用量
 *
 * <p>{@link #consumeStep()} 與 {@link #consumeSemanticCall()} 在額度用盡時丟例外，
 * 迫使迴圈先檢查 {@link #hasStepRemaining()}／{@link #hasSemanticCallRemaining()}</p>
 */
public record AttemptBudget(
        int maxSteps,
        int usedSteps,
        int maxSemanticCalls,
        int usedSemanticCalls) {

    public AttemptBudget {
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

    public static AttemptBudget of(int maxSteps, int maxSemanticCalls) {
        return new AttemptBudget(maxSteps, 0, maxSemanticCalls, 0);
    }

    public AttemptBudget consumeStep() {
        if (!hasStepRemaining()) {
            throw new IllegalArgumentException("analysis step budget is exhausted");
        }
        return new AttemptBudget(maxSteps, usedSteps + 1, maxSemanticCalls, usedSemanticCalls);
    }

    public AttemptBudget consumeSemanticCall() {
        if (!hasSemanticCallRemaining()) {
            throw new IllegalArgumentException("semantic call budget is exhausted");
        }
        return new AttemptBudget(maxSteps, usedSteps, maxSemanticCalls, usedSemanticCalls + 1);
    }

    public boolean hasStepRemaining() {
        return usedSteps < maxSteps;
    }

    public boolean hasSemanticCallRemaining() {
        return usedSemanticCalls < maxSemanticCalls;
    }
}
