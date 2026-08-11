package com.java.system.agent.capability.planning;

import java.util.List;

/**
 * 提供固定 PLAN、ANSWER 與 CLARIFY planning tool 的核心 registration
 */
public final class CorePlanningToolProvider implements PlanningToolProvider {

    private final List<PlanningToolRegistration<?>> registrations;

    public CorePlanningToolProvider() {
        this.registrations = List.of(
                new PlanPlanningToolRegistration<>("agent_plan_question", PlanQuestionPlanningInput.class,
                        new PlanQuestionPlanningMapper()),
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper()),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification", RequestClarificationPlanningInput.class,
                        new RequestClarificationPlanningMapper()));
    }

    @Override
    public List<PlanningToolRegistration<?>> registrations() {
        return registrations;
    }
}
