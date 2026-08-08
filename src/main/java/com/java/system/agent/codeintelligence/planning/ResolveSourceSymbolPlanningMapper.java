package com.java.system.agent.codeintelligence.planning;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;

import java.util.Optional;

/** 將來源符號模型欄位映射為未綁定 context 的 execution input */
public final class ResolveSourceSymbolPlanningMapper implements QueryPlanningMapper<ResolveSourceSymbolPlanningInput, ResolveSourceSymbolExecutionInput> {
    @Override
    public QueryPlanningSelection<ResolveSourceSymbolExecutionInput> map(ResolveSourceSymbolPlanningInput input) {
        Optional<com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos.Position> position =
                Optional.ofNullable(input.position()).orElse(Optional.empty());
        return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                input.questionToResolve(), input.rationale(),
                new ResolveSourceSymbolExecutionInput(input.symbol(), position, Optional.empty()));
    }
}
