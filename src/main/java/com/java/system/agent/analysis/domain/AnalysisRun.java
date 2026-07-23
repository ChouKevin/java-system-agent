package com.java.system.agent.analysis.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AnalysisRun(
        AnalysisRunId id,
        List<AnalysisAttempt> attempts,
        Optional<AnalysisOutcome> outcome) {

    public AnalysisRun {
        Objects.requireNonNull(id, "analysis run ID must not be null");
        Objects.requireNonNull(attempts, "analysis attempts must not be null");
        Objects.requireNonNull(outcome, "analysis outcome must not be null");
        attempts = attempts.stream()
                .map(attempt -> Objects.requireNonNull(attempt, "analysis attempt must not be null"))
                .toList();
        if (attempts.size() < 1) {
            throw new IllegalArgumentException("analysis run must contain at least one attempt");
        }
    }

    public static AnalysisRun start(AnalysisRunId id, AnalysisAttempt firstAttempt) {
        Objects.requireNonNull(firstAttempt, "first analysis attempt must not be null");
        return new AnalysisRun(id, List.of(firstAttempt), Optional.empty());
    }

    public AnalysisAttempt currentAttempt() {
        return attempts.getLast();
    }

    public AnalysisRun concludeCurrentAttempt(
            RevisionVector finalRevisionVector,
            AnalysisBudget finalBudget,
            AttemptOutcome terminalOutcome) {
        Objects.requireNonNull(finalRevisionVector, "final revision vector must not be null");
        Objects.requireNonNull(finalBudget, "final analysis budget must not be null");
        Objects.requireNonNull(terminalOutcome, "analysis attempt outcome must not be null");
        if (outcome.isPresent() || currentAttempt().outcome().isPresent()) {
            throw new IllegalArgumentException("current analysis attempt is already concluded");
        }
        AnalysisAttempt currentAttempt = currentAttempt();
        AnalysisAttempt activeAttemptWithFinalState = new AnalysisAttempt(
                currentAttempt.id(),
                finalRevisionVector,
                finalBudget,
                Optional.empty());
        AnalysisAttempt concludedAttempt = activeAttemptWithFinalState.conclude(terminalOutcome);
        List<AnalysisAttempt> updatedAttempts = new ArrayList<>(attempts);
        updatedAttempts.set(updatedAttempts.size() - 1, concludedAttempt);
        return new AnalysisRun(id, updatedAttempts, outcome);
    }

    public AnalysisRun replaceCurrentAttempt(
            AnalysisAttempt staleAttempt,
            AnalysisAttempt nextAttempt) {
        Objects.requireNonNull(staleAttempt, "stale analysis attempt must not be null");
        Objects.requireNonNull(nextAttempt, "next analysis attempt must not be null");
        if (outcome.isPresent()) {
            throw new IllegalArgumentException("concluded analysis run cannot start another attempt");
        }
        if (!currentAttempt().id().equals(staleAttempt.id())) {
            throw new IllegalArgumentException("only the current analysis attempt can be replaced");
        }
        if (!staleAttempt.outcome().filter(AttemptOutcome.STALE::equals).isPresent()) {
            throw new IllegalArgumentException("current analysis attempt must conclude as STALE");
        }
        if (nextAttempt.outcome().isPresent()) {
            throw new IllegalArgumentException("next analysis attempt must be active");
        }
        List<AnalysisAttempt> updatedAttempts = new ArrayList<>(attempts);
        updatedAttempts.set(updatedAttempts.size() - 1, staleAttempt);
        updatedAttempts.add(nextAttempt);
        return new AnalysisRun(id, updatedAttempts, outcome);
    }

    public AnalysisRun conclude(AnalysisOutcome terminalOutcome) {
        Objects.requireNonNull(terminalOutcome, "analysis outcome must not be null");
        if (outcome.isPresent()) {
            throw new IllegalArgumentException("analysis run is already concluded");
        }
        if (!currentAttempt().outcome().isPresent()) {
            throw new IllegalArgumentException("current analysis attempt must conclude before the run");
        }
        return new AnalysisRun(id, attempts, Optional.of(terminalOutcome));
    }
}
