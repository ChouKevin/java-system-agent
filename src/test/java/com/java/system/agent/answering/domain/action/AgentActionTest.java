package com.java.system.agent.answering.domain.action;

import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 尚未驗證 action 保留模型提交的 raw handle 值測試
 */
class AgentActionTest {

    @Test
    void preserves_raw_candidate_values_in_original_order() {
        List<CandidateHandleRef> candidates = new ArrayList<>(List.of(new CandidateHandleRef("candidate-1")));

        QueryAction action = new QueryAction(capability(), candidates, "Find the route",
                new CapabilityInputPayload("{}"), "Need the entry point");
        candidates.clear();

        assertThat(action.candidates()).containsExactly(new CandidateHandleRef("candidate-1"));
    }

    @Test
    void permits_clarification_without_candidates() {
        ClarifyAction action = new ClarifyAction("Which order route?", List.of(), "Multiple repositories remain plausible");

        assertThat(action.candidates()).isEmpty();
    }

    private CapabilityHandle capability() {
        return new CapabilityHandle("capability-1", new HandleBinding(new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), RevisionVector.empty()));
    }
}
