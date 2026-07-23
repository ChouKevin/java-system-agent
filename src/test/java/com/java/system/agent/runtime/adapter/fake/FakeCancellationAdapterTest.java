package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.analysis.domain.AnalysisRunId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FakeCancellationAdapterTest {

    private final AnalysisRunId runId = new AnalysisRunId("run-1");

    @Test
    void defaultRemainsFalseAndCountsChecks() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter();

        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.checkCount()).isEqualTo(2);
    }

    @Test
    void requestsCancellationAfterConfiguredNumberOfChecks() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter()
                .requestCancellationAfter(2);

        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.isCancellationRequested(runId)).isFalse();
        assertThat(adapter.isCancellationRequested(runId)).isTrue();
        assertThat(adapter.isCancellationRequested(runId)).isTrue();
        assertThat(adapter.checkCount()).isEqualTo(4);
    }

    @Test
    void zeroRequestsCancellationOnFirstCheck() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter()
                .requestCancellationAfter(0);

        assertThat(adapter.isCancellationRequested(runId)).isTrue();
        assertThat(adapter.checkCount()).isEqualTo(1);
    }

    @Test
    void rejectsNegativeThresholdAndNullRunId() {
        FakeCancellationAdapter adapter = new FakeCancellationAdapter();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.requestCancellationAfter(-1));
        assertThatNullPointerException()
                .isThrownBy(() -> adapter.isCancellationRequested(null));
        assertThat(adapter.checkCount()).isZero();
    }
}
