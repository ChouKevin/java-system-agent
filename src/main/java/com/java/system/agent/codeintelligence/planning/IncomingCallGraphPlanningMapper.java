package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class IncomingCallGraphPlanningMapper implements QueryPlanningMapper<IncomingCallGraphPlanningInput, IncomingCallGraphExecutionInput> {
    @Override
    public QueryPlanningSelection<IncomingCallGraphExecutionInput> map(IncomingCallGraphPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new IncomingCallGraphExecutionInput(java.util.Optional.ofNullable(input.depth()).orElse(2), input.target()));
    }
}
