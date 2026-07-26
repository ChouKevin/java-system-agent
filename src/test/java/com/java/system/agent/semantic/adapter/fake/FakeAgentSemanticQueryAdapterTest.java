package com.java.system.agent.semantic.adapter.fake;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.SemanticObservation;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.AgentSemanticQuery;
import com.java.system.agent.runtime.port.out.AgentSemanticQueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeAgentSemanticQueryAdapterTest {

    @Test
    void should_return_registered_typed_result_and_retain_exact_query_without_fallback() {
        AgentSemanticQuery query = query();
        SemanticObservation first = new SemanticObservation(ObservationCode.UNRESOLVED_CALL, "first observation",
                List.of(), List.of(), "fake");
        SemanticObservation second = new SemanticObservation(ObservationCode.UNRESOLVED_CALL, "second observation",
                List.of(), List.of(), "fake");
        AgentSemanticQueryResult result = new AgentSemanticQueryResult(List.of(), List.of(), List.of(first, second));
        FakeAgentSemanticQueryAdapter adapter = new FakeAgentSemanticQueryAdapter();
        adapter.register(query, result);

        assertThat(adapter.query(query)).isSameAs(result);
        assertThat(adapter.queries()).containsExactly(query);
        assertThat(result.observations()).containsExactly(first, second);
    }

    @Test
    void should_fail_when_no_exact_typed_query_is_registered() {
        FakeAgentSemanticQueryAdapter adapter = new FakeAgentSemanticQueryAdapter();

        assertThatThrownBy(() -> adapter.query(query()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no fake agent semantic result");
    }

    private AgentSemanticQuery query() {
        RepositoryId repositoryId = new RepositoryId("order-service");
        RevisionVector revisions = RevisionVector.empty();
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                revisions);
        CandidateHandle handle = new CandidateHandle("candidate-1", binding, CandidateKind.REPOSITORY);
        IssuedCandidate candidate = new IssuedCandidate(handle,
                new RepositoryCandidate(repositoryId, "repository candidate"));
        CapabilityDescriptor capability = new CapabilityDescriptor("lookup", "v1", Set.of(CandidateKind.REPOSITORY),
                1, 1, new CapabilityQuerySchema(List.of()));
        return new AgentSemanticQuery(capability, List.of(candidate), "question", Map.of(), revisions);
    }
}
