package com.java.system.agent.analysis.domain;

import java.util.Objects;
import java.util.Optional;

public record AnalysisAttempt(
        AnalysisAttemptId id,
        RevisionVector revisionVector,
        AnalysisBudget budget,
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
            AnalysisBudget budget) {
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
