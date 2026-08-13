package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

public final class DiscoverTypeMembersPlanningMapper implements QueryPlanningMapper<DiscoverTypeMembersPlanningInput, DiscoverTypeMembersExecutionInput> {
    @Override
    public QueryPlanningSelection<DiscoverTypeMembersExecutionInput> map(DiscoverTypeMembersPlanningInput input) {
        return new QueryPlanningSelection<>(input.questionToResolve(), input.rationale(),
                new DiscoverTypeMembersExecutionInput(input.sourceType(), input.memberKinds().stream().map(Enum::name).toList(),
                        input.namePrefix(), java.util.Optional.ofNullable(input.offset()).orElse(0),
                        java.util.Optional.ofNullable(input.limit()).orElse(50)));
    }
}
