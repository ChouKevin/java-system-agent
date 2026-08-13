package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class GetMethodSourcePlanningMapper implements QueryPlanningMapper<GetMethodSourcePlanningInput, GetMethodSourceExecutionInput> {
    @Override
    public QueryPlanningSelection<GetMethodSourceExecutionInput> map(GetMethodSourcePlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(), new GetMethodSourceExecutionInput(input.target()));
    }
}
