package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;
import com.java.system.agent.runtime.port.out.SemanticResultStatus;

import java.util.Objects;

/**
 * 判斷一次暫時性的語意查詢失敗值不值得重試
 *
 * <p>由 {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 在拿到語意
 * 查詢結果之後、交給 {@link SemanticResultInterpreter} 解讀之前呼叫，用來決定
 * {@code retryAllowed} 這個旗標</p>
 *
 * <p>純規則判斷：狀態必須是暫時性的、失敗本身標記為可重試、目前這個 information need
 * 累計呼叫次數未達上限，且 step 與語意呼叫預算都還有餘額，缺一不可</p>
 */
public final class SemanticRetryPolicy {

    private static final int MAX_TOTAL_CALLS_PER_NEED = 2;

    public boolean shouldRetry(
            SemanticQueryResult result,
            int callsSoFar,
            AttemptBudget budget) {
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
