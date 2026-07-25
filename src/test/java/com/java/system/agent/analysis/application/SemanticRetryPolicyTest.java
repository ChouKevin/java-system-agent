package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.port.out.SemanticFailure;
import com.java.system.agent.analysis.port.out.SemanticFailureCode;
import com.java.system.agent.analysis.port.out.SemanticQueryResult;
import com.java.system.agent.analysis.port.out.SemanticResultStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SemanticRetryPolicyTest {

    private final SemanticRetryPolicy policy = new SemanticRetryPolicy();

    @Test
    void permitsOnlyTheSecondTransientRetryWhenBothBudgetsRemain() {
        AnalysisBudget budget = new AnalysisBudget(3, 1, 3, 1);

        assertThat(policy.shouldRetry(transientResult(SemanticResultStatus.NOT_READY), 1, budget)).isTrue();
        assertThat(policy.shouldRetry(transientResult(SemanticResultStatus.TIMEOUT), 1, budget)).isTrue();
        assertThat(policy.shouldRetry(transientResult(SemanticResultStatus.NOT_READY), 2, budget)).isFalse();
    }

    @Test
    void rejectsRetriesWhenFailureIsNotRetryableOrStatusIsNotTransient() {
        SemanticQueryResult nonRetryableTimeout = new SemanticQueryResult(
                SemanticResultStatus.TIMEOUT,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(SemanticFailureCode.TIMEOUT, "timeout", false)));
        SemanticQueryResult forbidden = new SemanticQueryResult(
                SemanticResultStatus.FORBIDDEN,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(SemanticFailureCode.FORBIDDEN, "forbidden", false)));

        assertThat(policy.shouldRetry(nonRetryableTimeout, 1, AnalysisBudget.of(2, 2))).isFalse();
        assertThat(policy.shouldRetry(forbidden, 1, AnalysisBudget.of(2, 2))).isFalse();
    }

    @Test
    void rejectsRetriesWhenEitherStepOrSemanticCallBudgetIsExhausted() {
        SemanticQueryResult timeout = transientResult(SemanticResultStatus.TIMEOUT);
        AnalysisBudget stepExhausted = new AnalysisBudget(1, 1, 2, 1);
        AnalysisBudget semanticCallsExhausted = new AnalysisBudget(2, 1, 1, 1);

        assertThat(policy.shouldRetry(timeout, 1, stepExhausted)).isFalse();
        assertThat(policy.shouldRetry(timeout, 1, semanticCallsExhausted)).isFalse();
    }

    @Test
    void validatesRequiredArgumentsAndPositiveCallCount() {
        SemanticQueryResult timeout = transientResult(SemanticResultStatus.TIMEOUT);
        AnalysisBudget budget = AnalysisBudget.of(2, 2);

        assertThatNullPointerException().isThrownBy(() -> policy.shouldRetry(null, 1, budget));
        assertThatNullPointerException().isThrownBy(() -> policy.shouldRetry(timeout, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.shouldRetry(timeout, 0, budget));
    }

    private SemanticQueryResult transientResult(SemanticResultStatus status) {
        SemanticFailureCode failureCode = status == SemanticResultStatus.NOT_READY // cs-allow
                ? SemanticFailureCode.NOT_READY
                : SemanticFailureCode.TIMEOUT;
        return new SemanticQueryResult(
                status,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(failureCode, "temporarily unavailable", true)));
    }
}
