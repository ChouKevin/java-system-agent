package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

/**
 * 將 suggest-api-route planning 欄位映射為 raw 候選參考與 execution input
 */
public final class SuggestApiRoutePlanningMapper implements QueryPlanningMapper<SuggestApiRoutePlanningInput, SuggestApiRouteExecutionInput> {

    @Override
    public QueryPlanningSelection<SuggestApiRouteExecutionInput> map(SuggestApiRoutePlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new SuggestApiRouteExecutionInput(input.apiPath(), input.httpMethod(), input.limit()));
    }
}
