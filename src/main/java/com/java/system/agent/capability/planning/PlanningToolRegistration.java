package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.port.out.AgentPromptContext;

/**
 * planning tool catalog 的中立 registration 契約
 */
public sealed interface PlanningToolRegistration<I>
        permits QueryPlanningToolRegistration, AnswerPlanningToolRegistration, ClarifyPlanningToolRegistration,
        ExecutePlanningToolRegistration, FollowUpOnlyQueryRegistration, PlanPlanningToolRegistration {

    String name();

    PlanningToolDescriptor descriptor();

    Class<I> planningInputType();

    default boolean isIssued(AgentPromptContext context) {
        return true;
    }

    AgentAction toAction(I input, AgentPromptContext context);
}
