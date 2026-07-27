package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.CapabilityObservation;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeCapabilityExecutionAdapterTest {

    @Test
    void should_return_registered_typed_result_and_retain_exact_query_without_fallback() {
        CapabilityInvocation invocation = invocation();
        CapabilityObservation first = new CapabilityObservation(ObservationCode.UNRESOLVED_CALL, "first observation",
                List.of(), List.of(), "fake");
        CapabilityObservation second = new CapabilityObservation(ObservationCode.UNRESOLVED_CALL, "second observation",
                List.of(), List.of(), "fake");
        CapabilityExecutionResult result = new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of(first, second));
        FakeCapabilityExecutionAdapter adapter = new FakeCapabilityExecutionAdapter();
        adapter.register(invocation, result);

        assertThat(adapter.execute(invocation)).isSameAs(result);
        assertThat(adapter.invocations()).containsExactly(invocation);
        assertThat(((CapabilityExecutionResult.Succeeded) result).observations()).containsExactly(first, second);
    }

    @Test
    void should_fail_when_no_exact_typed_query_is_registered() {
        FakeCapabilityExecutionAdapter adapter = new FakeCapabilityExecutionAdapter();

        assertThatThrownBy(() -> adapter.execute(invocation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no fake capability result");
    }

    private CapabilityInvocation invocation() {
        RepositoryId repositoryId = new RepositoryId("order-service");
        RevisionVector revisions = RevisionVector.empty();
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"),
                revisions);
        CandidateHandle handle = new CandidateHandle("candidate-1", binding, CandidateKind.REPOSITORY);
        IssuedCandidate candidate = new IssuedCandidate(handle,
                new RepositoryCandidate(repositoryId, "repository candidate"));
        CapabilityDescriptor capability = new CapabilityDescriptor("lookup", "v1", Set.of(CandidateKind.REPOSITORY),
                1, 1, new CapabilityQuerySchema(List.of()));
        return new CapabilityInvocation(capability, List.of(candidate), "question", Map.of(), revisions);
    }
}
