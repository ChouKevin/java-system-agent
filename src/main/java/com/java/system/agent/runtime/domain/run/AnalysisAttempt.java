package com.java.system.agent.runtime.domain.run;

import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.Objects;
import java.util.Optional;

/**
 * 一組固定 revision 的分析嘗試
 *
 * <p>掛在 {@link AnalysisRun#attempts()} 之下；被 STALE 淘汰後由下一個 Attempt 取代，
 * 但仍保留在清單中作為歷史紀錄</p>
 *
 * <p>{@code outcome} 為空代表仍在執行中，{@link #conclude(AttemptOutcome)} 只能呼叫一次</p>
 */
public record AnalysisAttempt(
        AnalysisAttemptId id,
        RevisionVector revisionVector,
        AttemptBudget budget,
        Optional<AttemptOutcome> outcome) {

    public AnalysisAttempt {
        Objects.requireNonNull(id, "analysis attempt ID must not be null");
        Objects.requireNonNull(revisionVector, "revision vector must not be null");
        Objects.requireNonNull(budget, "analysis budget must not be null");
        Objects.requireNonNull(outcome, "analysis attempt outcome must not be null");
    }

    public static AnalysisAttempt start(
            AnalysisAttemptId id,
            RevisionVector revisionVector,
            AttemptBudget budget) {
        return new AnalysisAttempt(id, revisionVector, budget, Optional.empty());
    }

    public AnalysisAttempt conclude(AttemptOutcome terminalOutcome) {
        Objects.requireNonNull(terminalOutcome, "analysis attempt outcome must not be null");
        if (outcome.isPresent()) {
            throw new IllegalArgumentException("analysis attempt is already concluded");
        }
        return new AnalysisAttempt(id, revisionVector, budget, Optional.of(terminalOutcome));
    }
}
