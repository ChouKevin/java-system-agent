package com.java.system.agent.model.action.planning;

import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.handle.CandidateHandleRef;

import java.util.List;
import java.util.function.Function;

/**
 * 將 CLARIFY planning input 轉為保留原始 opaque candidate 值的未驗證 action
 */
public final class RequestClarificationPlanningMapper implements Function<RequestClarificationPlanningInput, ClarifyAction> {

    @Override
    public ClarifyAction apply(RequestClarificationPlanningInput input) {
        List<CandidateHandleRef> candidates = input.candidateHandles().stream().map(CandidateHandleRef::new).toList();
        return new ClarifyAction(input.question(), candidates, input.reason());
    }
}
