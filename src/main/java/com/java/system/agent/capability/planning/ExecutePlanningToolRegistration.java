package com.java.system.agent.capability.planning;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.port.out.AgentPromptContext;

import java.util.Optional;

/**
 * 將 execute_http planning input 映射為獨立 EXECUTE action
 */
public final class ExecutePlanningToolRegistration
        implements PlanningToolRegistration<ExecuteHttpPlanningInput> {

    private final ExecuteJsonBodyValidator bodyValidator = new ExecuteJsonBodyValidator();

    @Override
    public String name() {
        return "execute_http";
    }

    @Override
    public String description() {
        return "Agent EXECUTE preview HTTP mutation intent";
    }

    @Override
    public Class<ExecuteHttpPlanningInput> planningInputType() {
        return ExecuteHttpPlanningInput.class;
    }

    @Override
    public boolean isIssued(AgentPromptContext context) {
        return context.budget().hasExecuteExecutionRemaining();
    }

    @Override
    public AgentAction toAction(
            ExecuteHttpPlanningInput input,
            AgentPromptContext context) {
        Optional<String> jsonBody = Optional.ofNullable(input.jsonBody());
        bodyValidator.validate(jsonBody);
        return new ExecuteAction(
                input.method(),
                input.targetUrl(),
                jsonBody,
                input.rationale());
    }
}
