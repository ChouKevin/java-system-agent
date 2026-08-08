package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

import java.util.Optional;

/** 將方法原始碼模型輸入映射為未綁定 execution input */
public final class GetMethodSourcePlanningMapper implements QueryPlanningMapper<GetMethodSourcePlanningInput, GetMethodSourceExecutionInput> {
    @Override
    public QueryPlanningSelection<GetMethodSourceExecutionInput> map(GetMethodSourcePlanningInput input) {
        return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                input.questionToResolve(), input.rationale(), new GetMethodSourceExecutionInput(Optional.empty()));
    }
}
