package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class ResolveConceptPlanningMapper implements QueryPlanningMapper<ResolveConceptPlanningInput, ResolveConceptExecutionInput> {
    @Override
    public QueryPlanningSelection<ResolveConceptExecutionInput> map(ResolveConceptPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new ResolveConceptExecutionInput(SemanticPlanningIdentities.toWire(input.identity())));
    }
}
