package com.java.system.agent.answering.domain.action;

import com.java.system.agent.answering.domain.handle.CandidateHandleRef;

import java.util.List;
import java.util.Objects;

/**
 * 模型請使用者在候選參考範圍內補充資訊的未驗證動作
 * 問題、候選參考與理由來自模型，候選必須由 answering 以本輪 issued 值解析
 */
public record ClarifyAction(String question, List<CandidateHandleRef> candidates, String reason) implements AgentAction {
    public ClarifyAction {
        Objects.requireNonNull(question, "clarify action question must not be null");
        Objects.requireNonNull(candidates, "clarify action candidates must not be null");
        Objects.requireNonNull(reason, "clarify action reason must not be null");
        if (question.isBlank() || reason.isBlank()) throw new IllegalArgumentException("clarify action question and reason must not be blank");
        candidates = List.copyOf(candidates);
    }
}
