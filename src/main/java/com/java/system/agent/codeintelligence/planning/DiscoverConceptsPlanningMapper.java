package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

import java.util.Optional;

/** 將概念探索模型欄位映射為 provider execution input */
public final class DiscoverConceptsPlanningMapper implements QueryPlanningMapper<DiscoverConceptsPlanningInput, DiscoverConceptsExecutionInput> {
    @Override
    public QueryPlanningSelection<DiscoverConceptsExecutionInput> map(DiscoverConceptsPlanningInput input) {
        int offset = Optional.ofNullable(input.offset()).orElse(0);
        int limit = Optional.ofNullable(input.limit()).orElse(50);
        Optional<String> packagePrefix = Optional.ofNullable(input.packagePrefix()).orElse(Optional.empty());
        DiscoverConceptsExecutionInput execution = new DiscoverConceptsExecutionInput(
                input.terms(), input.kinds(), packagePrefix, offset, limit);
        return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                input.questionToResolve(), input.rationale(), execution);
    }
}
