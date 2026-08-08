package com.java.system.agent.capability.planning;

import java.util.List;

/**
 * 提供固定 ANSWER、CLARIFY 與 follow-up selector planning tool 的核心 registration
 */
public final class CorePlanningToolProvider implements PlanningToolProvider {

    private final List<PlanningToolRegistration<?>> registrations;

    public CorePlanningToolProvider() {
        this.registrations = List.of(
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper()),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification", RequestClarificationPlanningInput.class,
                        new RequestClarificationPlanningMapper()),
                new FollowUpPlanningToolRegistration());
    }

    @Override
    public List<PlanningToolRegistration<?>> registrations() {
        return registrations;
    }
}
