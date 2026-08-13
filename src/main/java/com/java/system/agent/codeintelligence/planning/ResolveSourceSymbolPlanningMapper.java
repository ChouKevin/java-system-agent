package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class ResolveSourceSymbolPlanningMapper implements QueryPlanningMapper<ResolveSourceSymbolPlanningInput, ResolveSourceSymbolExecutionInput> {
    @Override
    public QueryPlanningSelection<ResolveSourceSymbolExecutionInput> map(ResolveSourceSymbolPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new ResolveSourceSymbolExecutionInput(input.symbol(), input.position(), input.context()));
    }
}
