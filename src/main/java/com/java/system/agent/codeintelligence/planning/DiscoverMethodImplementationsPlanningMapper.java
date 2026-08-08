package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

import java.util.Optional;

/** 將方法實作探索模型輸入映射為未綁定 execution input */
public final class DiscoverMethodImplementationsPlanningMapper implements QueryPlanningMapper<DiscoverMethodImplementationsPlanningInput, DiscoverMethodImplementationsExecutionInput> {
    @Override
    public QueryPlanningSelection<DiscoverMethodImplementationsExecutionInput> map(DiscoverMethodImplementationsPlanningInput input) {
        return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                input.questionToResolve(), input.rationale(), new DiscoverMethodImplementationsExecutionInput(Optional.empty()));
    }
}
