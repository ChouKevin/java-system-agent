package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.AnalysisBudget;
import com.java.system.agent.runtime.domain.AnalysisRunId;
import com.java.system.agent.runtime.domain.AnalysisState;
import com.java.system.agent.runtime.domain.AnalysisStatus;
import com.java.system.agent.runtime.domain.AnalysisWarning;
import com.java.system.agent.runtime.domain.InformationNeedId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoProgressPolicyTest {

    @Test
    void terminatesAfterConfiguredNumberOfIdenticalFingerprints() {
        AnalysisState state = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));
        ProgressFingerprint fingerprint = ProgressFingerprint.from(state);
        NoProgressPolicy policy = new NoProgressPolicy(3);

        NoProgressEvaluation evaluation = policy.evaluate(
                List.of(fingerprint, fingerprint, fingerprint));

        assertThat(evaluation.terminate()).isTrue();
        assertThat(evaluation.blocker()).contains(GoalBlocker.NO_PROGRESS);
    }

    @Test
    void resetsConsecutiveCountWhenResolvedNeedsOrEvidenceChanges() {
        AnalysisState initial = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));
        AnalysisState progressed = GoalStateFixture.resolved(new InformationNeedId("need-entry-point"));
        ProgressFingerprint before = ProgressFingerprint.from(initial);
        ProgressFingerprint after = ProgressFingerprint.from(progressed);
        NoProgressPolicy policy = new NoProgressPolicy(3);

        NoProgressEvaluation evaluation = policy.evaluate(List.of(before, before, after));

        assertThat(after).isNotEqualTo(before);
        assertThat(evaluation.terminate()).isFalse();
        assertThat(evaluation.consecutiveNoProgress()).isEqualTo(1);
    }

    @Test
    void recordingANewWarningDoesNotCountAsProgress() {
        AnalysisState initial = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));
        AnalysisState warned = new AnalysisState(
                initial.runId(),
                initial.attemptId(),
                initial.stateRevision() + 1,
                initial.status(),
                initial.repositoryScope(),
                initial.revisionVector(),
                initial.pendingNeeds(),
                initial.resolvedNeedIds(),
                initial.evidenceBindings(),
                List.of(new AnalysisWarning("NEW_WARNING", "Still no useful progress")),
                initial.budget());

        assertThat(ProgressFingerprint.from(warned))
                .isEqualTo(ProgressFingerprint.from(initial));
    }

    @Test
    void changingOnlyANonTerminalRuntimeStatusDoesNotCountAsProgress() {
        AnalysisState initial = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));
        AnalysisState planning = GoalStateFixture.withStatus(initial, AnalysisStatus.PLANNING);

        assertThat(ProgressFingerprint.from(planning))
                .isEqualTo(ProgressFingerprint.from(initial));
    }

    @Test
    void terminalDiagnosisCountsAsProgress() {
        AnalysisState initial = AnalysisState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AnalysisBudget.of(5, 2));
        AnalysisState failed = GoalStateFixture.withStatus(initial, AnalysisStatus.FAILED);

        assertThat(ProgressFingerprint.from(failed))
                .isNotEqualTo(ProgressFingerprint.from(initial));
    }
}
