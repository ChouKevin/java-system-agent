package com.java.system.agent.answering.domain.action;

import com.java.system.agent.answering.domain.plan.QuestionPlan;

import java.util.Objects;

/**
 * 模型在執行其他動作前提出的問題解析計畫
 */
public record PlanAction(QuestionPlan plan) implements AgentAction {

    public PlanAction {
        Objects.requireNonNull(plan, "question plan must not be null");
    }
}
