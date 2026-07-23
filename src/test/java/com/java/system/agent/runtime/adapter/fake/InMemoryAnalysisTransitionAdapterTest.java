package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.analysis.application.AnalysisEvent;
import com.java.system.agent.analysis.application.StateTransition;
import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AttemptOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class InMemoryAnalysisTransitionAdapterTest {

    @Test
    void successfulCommitAppendsEventReturnsCandidateAndCountsCommit() {
        StateTransition transition = transition();
        InMemoryAnalysisTransitionAdapter adapter = new InMemoryAnalysisTransitionAdapter();

        AnalysisState committed = adapter.commit(transition);

        assertThat(committed).isSameAs(transition.candidateState());
        assertThat(adapter.events()).containsExactly(transition.event());
        assertThat(adapter.commitCount()).isEqualTo(1);
    }

    @Test
    void configuredFailureOccursBeforeEventAppendAndStillCountsAttempt() {
        StateTransition transition = transition();
        InMemoryAnalysisTransitionAdapter adapter = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(1);

        assertThatIllegalStateException()
                .isThrownBy(() -> adapter.commit(transition))
                .withMessageContaining("commit 1");
        assertThat(adapter.events()).isEmpty();
        assertThat(adapter.commitCount()).isEqualTo(1);
    }

    @Test
    void eventsAreImmutable() {
        StateTransition transition = transition();
        InMemoryAnalysisTransitionAdapter adapter = new InMemoryAnalysisTransitionAdapter();
        adapter.commit(transition);

        assertThat(adapter.events()).isUnmodifiable();
    }

    @Test
    void rejectsInvalidCommitNumberAndNullTransition() {
        InMemoryAnalysisTransitionAdapter adapter = new InMemoryAnalysisTransitionAdapter();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.failAtCommit(0));
        assertThatNullPointerException()
                .isThrownBy(() -> adapter.commit(null));
    }

    private StateTransition transition() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AnalysisState state = AnalysisState.initial(runId, attemptId, AnalysisBudget.of(10, 5));
        AnalysisEvent event = new AnalysisEvent.AttemptConcluded(
                runId,
                attemptId,
                state.stateRevision(),
                AttemptOutcome.COMPLETED);
        return new StateTransition(event, state.withStateRevision(1));
    }
}
