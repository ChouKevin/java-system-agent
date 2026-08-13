package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class GetEvidenceSourcePlanningMapper implements QueryPlanningMapper<GetEvidenceSourcePlanningInput, GetEvidenceSourceExecutionInput> {
    @Override
    public QueryPlanningSelection<GetEvidenceSourceExecutionInput> map(GetEvidenceSourcePlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new GetEvidenceSourceExecutionInput(SemanticPlanningIdentities.toWire(input.identity())));
    }
}
