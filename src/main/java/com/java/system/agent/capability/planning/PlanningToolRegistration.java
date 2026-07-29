package com.java.system.agent.capability.planning;

import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * planning tool catalog 中可產生 provider callback 的共用 registration 契約
 */
public sealed interface PlanningToolRegistration<I>
        permits QueryPlanningToolRegistration, AnswerPlanningToolRegistration, ClarifyPlanningToolRegistration {

    String name();

    Class<I> planningInputType();

    ToolCallback callback();

    default boolean isIssued(AgentPromptContext context) {
        return true;
    }

    AgentAction toAction(I input, AgentPromptContext context, CanonicalCapabilityPayloadCodec payloadCodec);

    static ToolCallback callback(String name, String description, String schema) {
        return new SchemaToolCallback(DefaultToolDefinition.builder().name(name).description(description).inputSchema(schema).build());
    }

    /**
     * 由 registration 的 canonical schema 建立且不會自動執行的 provider callback
     */
    record SchemaToolCallback(ToolDefinition definition) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return toolInput;
        }
    }
}
