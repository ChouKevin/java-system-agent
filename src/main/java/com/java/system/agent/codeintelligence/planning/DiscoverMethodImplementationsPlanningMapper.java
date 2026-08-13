package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class DiscoverMethodImplementationsPlanningMapper implements QueryPlanningMapper<DiscoverMethodImplementationsPlanningInput, DiscoverMethodImplementationsExecutionInput> {
    @Override
    public QueryPlanningSelection<DiscoverMethodImplementationsExecutionInput> map(DiscoverMethodImplementationsPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new DiscoverMethodImplementationsExecutionInput(input.target()));
    }
}
