package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class DiscoverConceptsPlanningMapper implements QueryPlanningMapper<DiscoverConceptsPlanningInput, DiscoverConceptsExecutionInput> {
    @Override
    public QueryPlanningSelection<DiscoverConceptsExecutionInput> map(DiscoverConceptsPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new DiscoverConceptsExecutionInput(input.terms().stream()
                        .map(term -> new DiscoverConceptsExecutionInput.Term(term.value(), term.matchMode().name())).toList(),
                        input.kinds().stream().map(Enum::name).toList(), input.packagePrefix(),
                        java.util.Optional.ofNullable(input.offset()).orElse(0),
                        java.util.Optional.ofNullable(input.limit()).orElse(50)));
    }
}
