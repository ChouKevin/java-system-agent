package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

/**
 * 將 list-entry-points planning 欄位映射為 raw 候選參考與 execution input
 */
public final class ListEntryPointsPlanningMapper implements QueryPlanningMapper<ListEntryPointsPlanningInput, ListEntryPointsExecutionInput> {

    @Override
    public QueryPlanningSelection<ListEntryPointsExecutionInput> map(ListEntryPointsPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new ListEntryPointsExecutionInput(input.type()));
    }
}
