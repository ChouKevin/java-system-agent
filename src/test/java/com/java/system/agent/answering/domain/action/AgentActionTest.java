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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * 尚未驗證 action 保留模型提交的 raw handle 值測試
 */
class AgentActionTest {

    @Test
    void preserves_the_candidate_free_query_execution_contract() {
        QueryAction action = new QueryAction(capability(), "Find the route", new CapabilityInputPayload("{}"),
                "Need the entry point");

        assertThat(action.questionToResolve()).isEqualTo("Find the route");
    }

    @Test
    void permits_clarification_without_candidates() {
        ClarifyAction action = new ClarifyAction("Which order route?", List.of(), "Multiple repositories remain plausible");

    }

    @Test
    void permits_all_supported_http_mutation_methods() {
        for (ExternalHttpMethod method : ExternalHttpMethod.values()) {
            ExecuteAction action = new ExecuteAction(method, "https://example.test/orders", Optional.of("{}"),
                    "Create the order");

            assertThat(action.method()).isEqualTo(method);
        }
    }

    @Test
    void rejects_missing_http_mutation_required_values() {
        assertThatNullPointerException().isThrownBy(() -> new ExecuteAction(null, "https://example.test/orders",
                Optional.empty(), "Create the order"));
        assertThatNullPointerException().isThrownBy(() -> new ExecuteAction(ExternalHttpMethod.POST, null,
                Optional.empty(), "Create the order"));
        assertThatNullPointerException().isThrownBy(() -> new ExecuteAction(ExternalHttpMethod.POST,
                "https://example.test/orders", null, "Create the order"));
        assertThatNullPointerException().isThrownBy(() -> new ExecuteAction(ExternalHttpMethod.POST,
                "https://example.test/orders", Optional.empty(), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExecuteAction(ExternalHttpMethod.POST, " ",
                Optional.empty(), "Create the order"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ExecuteAction(ExternalHttpMethod.POST,
                "https://example.test/orders", Optional.empty(), " "));
    }

    private CapabilityHandle capability() {
        return new CapabilityHandle("capability-1", new HandleBinding(new AnalysisRunId("run-1"),
                new AnalysisAttemptId("attempt-1"), RevisionVector.empty()));
    }
}
