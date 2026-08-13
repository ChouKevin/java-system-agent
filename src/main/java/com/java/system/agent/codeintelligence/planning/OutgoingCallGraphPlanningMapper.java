package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class OutgoingCallGraphPlanningMapper implements QueryPlanningMapper<OutgoingCallGraphPlanningInput, OutgoingCallGraphExecutionInput> {
    @Override
    public QueryPlanningSelection<OutgoingCallGraphExecutionInput> map(OutgoingCallGraphPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new OutgoingCallGraphExecutionInput(java.util.Optional.ofNullable(input.depth()).orElse(2), input.target()));
    }
}
