package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisBudget;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;
import com.java.system.agent.runtime.port.out.SemanticResultStatus;

import java.util.Objects;

public final class SemanticRetryPolicy {

    private static final int MAX_TOTAL_CALLS_PER_NEED = 2;

    public boolean shouldRetry(
            SemanticQueryResult result,
            int callsSoFar,
            AnalysisBudget budget) {
        Objects.requireNonNull(result, "semantic query result must not be null");
        Objects.requireNonNull(budget, "analysis budget must not be null");
        if (callsSoFar < 1) {
            throw new IllegalArgumentException("semantic call count must be positive");
        }
        return isTransient(result.status())
                && result.failure().filter(failure -> failure.retryable()).isPresent()
                && callsSoFar < MAX_TOTAL_CALLS_PER_NEED
                && budget.hasStepRemaining()
                && budget.hasSemanticCallRemaining();
    }

    private boolean isTransient(SemanticResultStatus status) {
        return switch (status) {
            case NOT_READY, TIMEOUT -> true;
            default -> false;
        };
    }
}
