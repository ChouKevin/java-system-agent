package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class DiscoverEventListenersPlanningMapper implements QueryPlanningMapper<DiscoverEventListenersPlanningInput, DiscoverEventListenersExecutionInput> {
    @Override
    public QueryPlanningSelection<DiscoverEventListenersExecutionInput> map(DiscoverEventListenersPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new DiscoverEventListenersExecutionInput(input.eventType(), java.util.Optional.ofNullable(input.offset()).orElse(0),
                        java.util.Optional.ofNullable(input.limit()).orElse(50)));
    }
}
