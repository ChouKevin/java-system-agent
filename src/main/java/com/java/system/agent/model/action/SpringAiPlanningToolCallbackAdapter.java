package com.java.system.agent.model.action;

import com.java.system.agent.capability.planning.IssuedPlanningTool;
import com.java.system.agent.capability.planning.PlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 將中立 planning registration 投影並快取為 Spring AI callback 的外部 adapter
 */
public final class SpringAiPlanningToolCallbackAdapter {

    private final PlanningToolRegistry registry;
    private final Map<String, ToolCallback> callbacksByName;

    public SpringAiPlanningToolCallbackAdapter(
            PlanningToolRegistry registry,
            PlanningToolSchemaFactory schemaFactory,
            PromptResourceCatalog promptCatalog) {
        this.registry = Objects.requireNonNull(registry, "planning tool registry must not be null");
        PlanningToolSchemaFactory requiredSchemaFactory = Objects.requireNonNull(
                schemaFactory, "planning schema factory must not be null");
        PromptResourceCatalog requiredPromptCatalog = Objects.requireNonNull(promptCatalog,
                "prompt resource catalog must not be null");
        this.callbacksByName = project(registry.registrations(), requiredSchemaFactory, requiredPromptCatalog);
    }

    public List<ToolCallback> issuedCallbacks(AgentPromptContext context) {
        return issuedTools(context).callbacks();
    }

    IssuedPlanningTools issuedTools(AgentPromptContext context) {
        Objects.requireNonNull(context, "agent prompt context must not be null");
        List<IssuedPlanningTool> issuedTools = registry.issuedTools(context);
        List<String> names = issuedTools.stream()
                .map(issuedTool -> Objects.requireNonNull(issuedTool,
                        "issued planning tool must not be null").name())
                .toList();
        if (names.stream().distinct().count() != names.size()) {
            throw new AgentActionContractException("duplicate issued planning tool name",
                    new IllegalStateException("issued planning tool name is duplicated"));
        }
        List<ToolCallback> callbacks = names.stream().map(this::callback).toList();
        return new IssuedPlanningTools(names, callbacks);
    }

    private ToolCallback callback(String name) {
        ToolCallback callback = callbacksByName.get(name);
        if (Objects.isNull(callback)) {
            throw new AgentActionContractException("issued planning registration has no callback projection",
                    new IllegalStateException("callback projection is absent"));
        }
        return callback;
    }

    private static Map<String, ToolCallback> project(
            List<PlanningToolRegistration<?>> registrations,
            PlanningToolSchemaFactory schemaFactory,
            PromptResourceCatalog promptCatalog) {
        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        for (PlanningToolRegistration<?> registration : registrations) {
            PlanningToolRegistration<?> required = Objects.requireNonNull(
                    registration, "planning registration must not contain null");
            ToolCallback callback = callback(required, schemaFactory, promptCatalog);
            if (Objects.nonNull(callbacks.putIfAbsent(required.name(), callback))) {
                throw new AgentActionContractException("duplicate planning callback projection",
                        new IllegalStateException("callback projection is duplicated"));
            }
        }
        return Collections.unmodifiableMap(callbacks);
    }

    private static ToolCallback callback(
            PlanningToolRegistration<?> registration,
            PlanningToolSchemaFactory schemaFactory,
            PromptResourceCatalog promptCatalog) {
        String schema = schemaFactory.createSchema(registration.planningInputType());
        ToolCallback callback = new SchemaToolCallback(DefaultToolDefinition.builder()
                .name(registration.name())
                .description(promptCatalog.toolDescription(registration.descriptor()))
                .inputSchema(schema)
                .build());
        return callback;
    }

    /**
     * 保持 callback 僅傳回輸入而不觸發 capability 執行
     */
    private record SchemaToolCallback(ToolDefinition definition) implements ToolCallback {

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
