package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class GetSourceSegmentPlanningMapper implements QueryPlanningMapper<GetSourceSegmentPlanningInput, GetSourceSegmentExecutionInput> {
    @Override
    public QueryPlanningSelection<GetSourceSegmentExecutionInput> map(GetSourceSegmentPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new GetSourceSegmentExecutionInput(input.location(),
                        java.util.Optional.ofNullable(input.contextLines()).orElse(0)));
    }
}
