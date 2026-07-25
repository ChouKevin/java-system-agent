package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.AnalysisBudget;
import com.java.system.agent.runtime.domain.AnalysisRunId;
import com.java.system.agent.runtime.domain.AnalysisState;
import com.java.system.agent.runtime.domain.AnalysisStatus;
import com.java.system.agent.runtime.domain.RepositoryScope;
import com.java.system.agent.runtime.domain.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AnalysisExecutionExceptionTest {

    @Test
    void retainsTerminationDataAndTheOriginalCause() {
        IllegalStateException cause = new IllegalStateException("transition persistence failed");
        AnalysisState lastCommittedState = state();

        AnalysisExecutionException exception = new AnalysisExecutionException(
                AnalysisTerminationReason.RUNTIME_FAILURE, lastCommittedState, cause);

        assertThat(exception).hasMessage("analysis execution stopped before a terminal state could be committed");
        assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
        assertThat(exception.lastCommittedState()).isSameAs(lastCommittedState);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void acceptsAnAbsentCause() {
        AnalysisExecutionException exception = new AnalysisExecutionException(
                AnalysisTerminationReason.CANCELLED, state(), null);

        assertThat(exception.getCause()).isNull();
    }

    @Test
    void rejectsNullTerminationReasonOrLastCommittedState() {
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionException(
                null, state(), new IllegalStateException("failure")));
        assertThatNullPointerException().isThrownBy(() -> new AnalysisExecutionException(
                AnalysisTerminationReason.RUNTIME_FAILURE, null, new IllegalStateException("failure")));
    }

    private AnalysisState state() {
        return new AnalysisState(
                new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"),
                1,
                AnalysisStatus.EXECUTING,
                RepositoryScope.of(List.of()),
                RevisionVector.empty(),
                Collections.emptySortedMap(),
                Set.of(),
                List.of(),
                List.of(),
                AnalysisBudget.of(10, 5));
    }
}
