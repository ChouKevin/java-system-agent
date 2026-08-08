package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;

import java.util.Optional;

/**
 * 將 outgoing-call-graph planning 欄位映射為 raw 候選參考與 execution input
 */
public final class OutgoingCallGraphPlanningMapper implements QueryPlanningMapper<OutgoingCallGraphPlanningInput, OutgoingCallGraphExecutionInput> {

    @Override
    public QueryPlanningSelection<OutgoingCallGraphExecutionInput> map(OutgoingCallGraphPlanningInput input) {
        int depth = Optional.ofNullable(input.depth()).orElse(2);
        return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                input.questionToResolve(), input.rationale(), new OutgoingCallGraphExecutionInput(depth, Optional.empty()));
    }
}
