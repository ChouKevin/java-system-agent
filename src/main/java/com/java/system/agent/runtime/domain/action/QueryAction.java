package com.java.system.agent.runtime.domain.action;

import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandleRef;
import com.java.system.agent.runtime.domain.capability.CapabilityInputPayload;

import java.util.List;
import java.util.Objects;

/**
 * 模型要求以 issued callback 的 capability 執行查詢的未驗證動作
 * capability 來自 runtime，候選參考、問題、payload 與理由來自模型且尚未經 runtime 驗證
 */
public record QueryAction(CapabilityHandle capability, List<CandidateHandleRef> candidates, String questionToResolve,
        CapabilityInputPayload payload, String rationale) implements AgentAction {
    public QueryAction {
        Objects.requireNonNull(capability, "query action capability must not be null");
        Objects.requireNonNull(candidates, "query action candidates must not be null");
        Objects.requireNonNull(questionToResolve, "query action question must not be null");
        Objects.requireNonNull(payload, "query action payload must not be null");
        Objects.requireNonNull(rationale, "query action rationale must not be null");
        if (questionToResolve.isBlank() || rationale.isBlank()) throw new IllegalArgumentException("query action question and rationale must not be blank");
        candidates = List.copyOf(candidates);
    }
}
