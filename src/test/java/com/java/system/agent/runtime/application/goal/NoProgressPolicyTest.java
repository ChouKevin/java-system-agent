package com.java.system.agent.runtime.application.goal;

import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptStatus;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoProgressPolicyTest {

    @Test
    void terminatesAfterConfiguredNumberOfIdenticalFingerprints() {
        AttemptState state = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));
        ProgressFingerprint fingerprint = ProgressFingerprint.from(state);
        NoProgressPolicy policy = new NoProgressPolicy(3);

        NoProgressEvaluation evaluation = policy.evaluate(
                List.of(fingerprint, fingerprint, fingerprint));

        assertThat(evaluation.terminate()).isTrue();
        assertThat(evaluation.blocker()).contains(GoalBlockReason.NO_PROGRESS);
    }

    @Test
    void resetsConsecutiveCountWhenResolvedNeedsOrEvidenceChanges() {
        AttemptState initial = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));
        AttemptState progressed = GoalStateFixture.resolved(new InformationNeedId("need-entry-point"));
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
        AttemptState initial = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));
        AttemptState warned = new AttemptState(
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
        AttemptState initial = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));
        AttemptState planning = GoalStateFixture.withStatus(initial, AttemptStatus.PLANNING);

        assertThat(ProgressFingerprint.from(planning))
                .isEqualTo(ProgressFingerprint.from(initial));
    }

    @Test
    void terminalDiagnosisCountsAsProgress() {
        AttemptState initial = AttemptState.initial(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                AttemptBudget.of(5, 2));
        AttemptState failed = GoalStateFixture.withStatus(initial, AttemptStatus.FAILED);

        assertThat(ProgressFingerprint.from(failed))
                .isNotEqualTo(ProgressFingerprint.from(initial));
    }
}
