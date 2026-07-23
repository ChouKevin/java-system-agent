package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FakeAttemptIdGeneratorTest {

    private final AnalysisRunId runId = new AnalysisRunId("run-1");

    @Test
    void returnsRegisteredIdsInOrderForValidRequests() {
        AnalysisAttemptId first = new AnalysisAttemptId("attempt-1");
        AnalysisAttemptId second = new AnalysisAttemptId("attempt-2");
        FakeAttemptIdGenerator generator = new FakeAttemptIdGenerator().register(first, second);

        assertThat(generator.nextAttemptId(runId, 1)).isSameAs(first);
        assertThat(generator.nextAttemptId(runId, 2)).isSameAs(second);
    }

    @Test
    void exhaustedSequenceFailsClosed() {
        FakeAttemptIdGenerator generator = new FakeAttemptIdGenerator()
                .register(new AnalysisAttemptId("attempt-1"));
        generator.nextAttemptId(runId, 1);

        assertThatIllegalStateException()
                .isThrownBy(() -> generator.nextAttemptId(runId, 2))
                .withMessageContaining("exhausted");
    }

    @Test
    void rejectsNullAndEmptyRegistrations() {
        FakeAttemptIdGenerator generator = new FakeAttemptIdGenerator();
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");

        assertThatNullPointerException()
                .isThrownBy(() -> generator.register((AnalysisAttemptId[]) null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> generator.register());
        assertThatNullPointerException()
                .isThrownBy(() -> generator.register(attemptId, null));
    }

    @Test
    void rejectsInvalidRequestsWithoutConsumingRegisteredId() {
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        FakeAttemptIdGenerator generator = new FakeAttemptIdGenerator().register(attemptId);

        assertThatNullPointerException()
                .isThrownBy(() -> generator.nextAttemptId(null, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> generator.nextAttemptId(runId, 0));

        assertThat(generator.nextAttemptId(runId, 1)).isSameAs(attemptId);
    }
}
