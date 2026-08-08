package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

import java.util.Optional;

/** 將事件監聽器模型欄位映射為 execution input */
public final class DiscoverEventListenersPlanningMapper implements QueryPlanningMapper<DiscoverEventListenersPlanningInput, DiscoverEventListenersExecutionInput> {
    @Override
    public QueryPlanningSelection<DiscoverEventListenersExecutionInput> map(DiscoverEventListenersPlanningInput input) {
        int offset = Optional.ofNullable(input.offset()).orElse(0);
        int limit = Optional.ofNullable(input.limit()).orElse(50);
        return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                input.questionToResolve(), input.rationale(),
                new DiscoverEventListenersExecutionInput(input.eventType(), offset, limit));
    }
}
