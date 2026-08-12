package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.List;
import java.util.Objects;

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

    default List<CandidateHandleRef> allowedCandidateHandles(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        return List.of();
    }

    AgentAction toAction(I input, AgentPromptContext context);
}
