package com.java.system.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.codebase.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import jakarta.validation.Validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 根設定產生的 capability tool schema 邊界測試
 */
class AgentCapabilityConfigurationTest {

    @Test
    void advertisesRequiredArgumentsFromActualLookupAndSuggestToolDefinitions() throws Exception {
        PlanningToolRegistry registry = new AgentCapabilityConfiguration().planningToolRegistry(
                mock(JavaSemanticServiceHttpAdapter.class), new ObjectMapper(),
                Validation.buildDefaultValidatorFactory().getValidator());
        Map<String, JsonNode> schemas = schemasByToolName(registry);

        assertThat(required(schemas, "codebase_lookup_api_route")).contains("apiPath").doesNotContain("httpMethod");
        assertThat(required(schemas, "codebase_suggest_api_route")).contains("apiPath", "limit").doesNotContain("httpMethod");
        assertThat(required(schemas, "codebase_list_entry_points")).doesNotContain("type");
        assertThat(required(schemas, "codebase_outgoing_call_graph")).doesNotContain("depth");
        assertThat(required(schemas, "codebase_incoming_call_graph")).doesNotContain("depth");
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry) throws Exception {
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());
        LinkedHashMap<CapabilityHandle, CapabilityPolicy> policies = new LinkedHashMap<>();
        int sequence = 1;
        for (CapabilityPolicy policy : registry.availableCapabilities()) {
            policies.put(new CapabilityHandle("capability-" + sequence, binding), policy);
            sequence++;
        }
        AgentPromptContext context = new AgentPromptContext("Find routes", SessionHistory.empty(), binding.runId(), binding.attemptId(),
                policies, Map.of(), Map.of(), Map.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0));
        ObjectMapper objectMapper = new ObjectMapper();
        LinkedHashMap<String, JsonNode> schemas = new LinkedHashMap<>();
        for (ToolCallback callback : registry.issuedCallbacks(context)) {
            schemas.put(callback.getToolDefinition().name(), objectMapper.readTree(callback.getToolDefinition().inputSchema()));
        }
        return Map.copyOf(schemas);
    }

    private static List<String> required(Map<String, JsonNode> schemas, String toolName) {
        JsonNode schema = schemas.get(toolName);
        return schema.path("required").valueStream().map(JsonNode::textValue).toList();
    }
}
