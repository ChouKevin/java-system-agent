package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class FindInternalReferencesPlanningMapper implements QueryPlanningMapper<FindInternalReferencesPlanningInput, FindInternalReferencesExecutionInput> {
    @Override
    public QueryPlanningSelection<FindInternalReferencesExecutionInput> map(FindInternalReferencesPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new FindInternalReferencesExecutionInput(input.target(), java.util.Optional.ofNullable(input.offset()).orElse(0),
                        java.util.Optional.ofNullable(input.limit()).orElse(50)));
    }
}
