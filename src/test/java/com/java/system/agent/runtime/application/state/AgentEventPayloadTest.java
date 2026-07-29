package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RuntimeNoticeReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent event payload 的 runtime notice 語意測試
 */
class AgentEventPayloadTest {

    @Test
    void preserves_rejection_description_without_final_response_mode() {
        String description = "  retry after revision drift  ";
        AgentEvent.ActionRejected rejected = new AgentEvent.ActionRejected(runId(), attemptId(), 0,
                Optional.empty(), description);

        assertThat(rejected.description()).isEqualTo(description);
    }

    @Test
    void preserves_typed_runtime_notice_reason() {
        AgentEvent.RunConcluded concluded = new AgentEvent.RunConcluded(runId(), attemptId(), 0,
                RunOutcome.INCONCLUSIVE, Optional.of(RuntimeNoticeReason.INSUFFICIENT_VERIFIABLE_INFORMATION), Optional.empty());

        assertThat(concluded.runtimeNoticeReason()).contains(RuntimeNoticeReason.INSUFFICIENT_VERIFIABLE_INFORMATION);
    }

    private static AnalysisRunId runId() {
        return new AnalysisRunId("run-1");
    }

    private static AnalysisAttemptId attemptId() {
        return new AnalysisAttemptId("attempt-1");
    }
}
