package com.java.system.agent.codebase.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.runtime.domain.handle.CandidateHandleRef;

/**
 * 將 lookup-api-route planning 欄位映射為 raw 候選參考與 execution input
 */
public final class LookupApiRoutePlanningMapper implements QueryPlanningMapper<LookupApiRoutePlanningInput, LookupApiRouteExecutionInput> {

    @Override
    public QueryPlanningSelection<LookupApiRouteExecutionInput> map(LookupApiRoutePlanningInput input) {
        return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                input.questionToResolve(), input.rationale(), new LookupApiRouteExecutionInput(input.apiPath(), input.httpMethod()));
    }
}
