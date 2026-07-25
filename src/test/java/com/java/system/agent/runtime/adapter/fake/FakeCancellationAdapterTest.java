package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.AnalysisRunId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FakeCancellationAdapterTest {

    private final AnalysisRunId runId = new AnalysisRunId("run-1");
    private final AnalysisRunId otherRunId = new AnalysisRunId("run-2");

    @Test
    void defaultRemainsFalseAndCountsChecks() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter();

        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.checkCount(runId)).isEqualTo(2);
    }

    @Test
    void requestsCancellationAfterConfiguredNumberOfChecks() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter()
                .requestCancellationAfter(runId, 2);

        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.isCancellationRequested(runId)).isTrue();
        assertThat(adapter.isCancellationRequested(runId)).isTrue();
        assertThat(adapter.checkCount(runId)).isEqualTo(4);
    }

    @Test
    void zeroRequestsCancellationOnFirstCheck() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter()
                .requestCancellationAfter(runId, 0);

        assertThat(adapter.isCancellationRequested(runId)).isTrue();
        assertThat(adapter.checkCount(runId)).isEqualTo(1);
    }

    @Test
    void isolatesCancellationConfigurationAndCountsByRun() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter()
                .requestCancellationAfter(runId, 1)
                .requestCancellationAfter(otherRunId, 3);

        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.isCancellationRequested(otherRunId)).isFalse();
        assertThat(adapter.isCancellationRequested(runId)).isTrue();
        assertThat(adapter.isCancellationRequested(otherRunId)).isFalse();
        assertThat(adapter.isCancellationRequested(otherRunId)).isFalse();
        assertThat(adapter.isCancellationRequested(otherRunId)).isTrue();
        assertThat(adapter.checkCount(runId)).isEqualTo(2);
        assertThat(adapter.checkCount(otherRunId)).isEqualTo(4);
    }

    @Test
    void rejectsInvalidConfigurationAndQueries() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.requestCancellationAfter(runId, -1));
        assertThatNullPointerException()
                .isThrownBy(() -> adapter.requestCancellationAfter(null, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> adapter.isCancellationRequested(null));
        assertThatNullPointerException()
                .isThrownBy(() -> adapter.checkCount(null));
        assertThat(adapter.checkCount(runId)).isZero();
    }
}
