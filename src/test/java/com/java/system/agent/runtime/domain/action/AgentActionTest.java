package com.java.system.agent.runtime.domain.action;

import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionTest {

    @Test
    void preservesExactlyOneCandidateListInItsOriginalOrder() {
        CandidateHandle first = candidate("candidate-1");
        List<CandidateHandle> candidates = new ArrayList<>(List.of(first));

        QueryAction action = new QueryAction(
                capability(), candidates, "Find the route", Map.of("depth", "1"), "Need the entry point");
        candidates.clear();

        assertThat(action.candidates()).containsExactly(first);
    }

    @Test
    void preservesSubsetAndAllListsIncludingDuplicates() {
        CandidateHandle first = candidate("candidate-1");
        CandidateHandle second = candidate("candidate-2");

        QueryAction subset = new QueryAction(capability(), List.of(second, first), "Find route", Map.of(), "reason");
        QueryAction all = new QueryAction(capability(), List.of(first, second, first), "Find route", Map.of(), "reason");

        assertThat(subset.candidates()).containsExactly(second, first);
        assertThat(all.candidates()).containsExactly(first, second, first);
    }

    @Test
    void permitsClarificationWithoutCandidates() {
        ClarifyAction action = new ClarifyAction("Which order route?", List.of(), "Multiple repositories remain plausible");

        assertThat(action.candidates()).isEmpty();
    }

    private CapabilityHandle capability() {
        return new CapabilityHandle("capability-1", binding());
    }

    private CandidateHandle candidate(String value) {
        return new CandidateHandle(value, binding(), CandidateKind.REPOSITORY);
    }

    private HandleBinding binding() {
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());
    }
}
