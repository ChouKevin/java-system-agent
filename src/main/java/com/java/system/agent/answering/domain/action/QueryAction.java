package com.java.system.agent.answering.domain.action;

import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;

import java.util.Objects;

/**
 * 模型要求以 issued callback 的 capability 執行查詢的未驗證動作
 * capability 來自 answering，問題、payload 與理由來自模型且尚未經 answering 驗證
 */
public record QueryAction(
        CapabilityHandle capability,
        String questionToResolve,
        CapabilityInputPayload payload,
        String rationale) implements AgentAction {
    public QueryAction {
        Objects.requireNonNull(capability, "query action capability must not be null");
        Objects.requireNonNull(questionToResolve, "query action question must not be null");
        Objects.requireNonNull(payload, "query action payload must not be null");
        Objects.requireNonNull(rationale, "query action rationale must not be null");
        if (questionToResolve.isBlank() || rationale.isBlank()) {
            throw new IllegalArgumentException("query action question and rationale must not be blank");
        }
    }
}
