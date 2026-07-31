package com.java.system.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.model.action.SpringAiPlanningToolCallbackAdapter;
import com.java.system.agent.model.action.SpringAiPlanningToolSchemaFactory;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
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
        PlanningToolRegistry registry = registry();
        Map<String, JsonNode> schemas = schemasByToolName(registry);

        assertThat(required(schemas, "codebase_lookup_api_route")).contains("apiPath").doesNotContain("httpMethod");
        assertThat(required(schemas, "codebase_suggest_api_route")).contains("apiPath", "limit").doesNotContain("httpMethod");
        assertThat(required(schemas, "codebase_list_entry_points")).doesNotContain("type");
        assertThat(required(schemas, "codebase_outgoing_call_graph")).doesNotContain("depth");
        assertThat(required(schemas, "codebase_incoming_call_graph")).doesNotContain("depth");
        assertThat(schemas).hasSize(7).containsKeys("agent_submit_answer", "agent_request_clarification");
        assertThat(required(schemas, "agent_submit_answer")).containsExactly("statements");
        assertThat(required(schemas, "agent_request_clarification"))
                .containsExactlyInAnyOrder("question", "candidateHandles", "reason");
        for (Map.Entry<String, JsonNode> entry : schemas.entrySet()) {
            if (!entry.getKey().startsWith("codebase_")) {
                continue;
            }
            JsonNode schema = entry.getValue();
            assertThat(schema.path("required")).extracting(jsonNode -> jsonNode.asText()).contains("candidateHandles");
            assertThat(schema.path("properties").path("candidateHandles").path("items").path("minLength").asInt())
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void startupRegistryVerifiesTheNestedAnswerStatementSchemaContract() throws Exception {
        PlanningToolRegistry registry = registry();
        JsonNode statement = schemasByToolName(registry).get("agent_submit_answer")
                .path("properties").path("statements").path("items");

        assertThat(statement.path("additionalProperties").asBoolean()).isFalse();
        assertThat(statement.path("required")).extracting(jsonNode -> jsonNode.asText())
                .containsExactlyInAnyOrder("statementId", "type", "text", "citationHandles", "observationIds");
        assertThat(statement.path("properties").path("text").path("minLength").asInt()).isEqualTo(1);
        assertThat(statement.path("properties").path("citationHandles").path("items").path("minLength").asInt())
                .isEqualTo(1);
    }

    @Test
    void maps_blank_registered_candidate_handle_to_invalid_tool_input_before_its_mapper() {
        PlanningToolRegistry registry = registry();
        CapabilityPolicy policy = registry.availableCapabilities().stream()
                .filter(value -> value.name().equals("codebase_lookup_api_route"))
                .findFirst()
                .orElseThrow();
        AgentPromptContext context = contextFor(List.of(policy));

        AgentActionProposal proposal = registry.interpretToolCall(policy.name(), """
                        {"candidateHandles":[" "],"questionToResolve":"Find the route","rationale":"Lookup the route","apiPath":"/orders"}
                        """, context);

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
    }

    @Test
    void mapsFixedPlanningToolsWithoutResolvingRawIssuedHandleReferences() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext context = contextFor(List.of());

        AgentActionProposal answerProposal = registry.interpretToolCall("agent_submit_answer", """
                        {"statements":[{"statementId":"statement-1","type":"FACT","text":"The route is called by checkout","claimId":"claim-1","citationHandles":["evidence-unknown"],"observationIds":["observation-1"]}]}
                        """, context);
        AgentActionProposal clarifyProposal = registry.interpretToolCall("agent_request_clarification", """
                        {"question":"Which repository?","candidateHandles":["candidate-2","candidate-1"],"reason":"The route scope is ambiguous"}
                        """, context);

        assertThat(answerProposal).isInstanceOf(AgentActionProposal.Proposed.class);
        AnswerAction answer = (AnswerAction) ((AgentActionProposal.Proposed) answerProposal).action();
        assertThat(answer.document().statements().getFirst().citations())
                .extracting(evidenceHandleReference -> evidenceHandleReference.value()).containsExactly("evidence-unknown");
        assertThat(clarifyProposal).isInstanceOf(AgentActionProposal.Proposed.class);
        ClarifyAction clarify = (ClarifyAction) ((AgentActionProposal.Proposed) clarifyProposal).action();
        assertThat(clarify.candidates()).extracting(candidateHandleReference -> candidateHandleReference.value())
                .containsExactly("candidate-2", "candidate-1");
    }

    @Test
    void isolates_planning_schema_and_decoder_from_a_customized_host_object_mapper() throws Exception {
        ObjectMapper hostMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new SimpleModule().addDeserializer(String.class,
                        new JsonDeserializer<>() {
                            @Override
                            public String deserialize(JsonParser parser, DeserializationContext context) {
                                return "host-customized";
                            }
                        }));
        PlanningToolRegistry registry = registry();
        CapabilityPolicy policy = registry.availableCapabilities().stream()
                .filter(value -> value.name().equals("codebase_lookup_api_route"))
                .findFirst()
                .orElseThrow();

        JsonNode schema = schemasByToolName(registry, List.of(policy)).get(policy.name());
        AgentActionProposal proposal = registry.interpretToolCall(policy.name(), """
                        {"candidateHandles":["candidate-1"],"questionToResolve":"Find the route","rationale":"Lookup the route","apiPath":"/orders"}
                        """, contextFor(List.of(policy)));

        assertThat(hostMapper.getPropertyNamingStrategy()).isEqualTo(PropertyNamingStrategies.SNAKE_CASE);
        assertThat(schema.path("properties").has("apiPath")).isTrue();
        assertThat(schema.path("properties").has("api_path")).isFalse();
        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry) throws Exception {
        return schemasByToolName(registry, registry.availableCapabilities());
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry, List<CapabilityPolicy> policies) throws Exception {
        AgentPromptContext context = contextFor(policies);
        ObjectMapper objectMapper = new ObjectMapper();
        LinkedHashMap<String, JsonNode> schemas = new LinkedHashMap<>();
        SpringAiPlanningToolCallbackAdapter callbackAdapter = new SpringAiPlanningToolCallbackAdapter(
                registry, new SpringAiPlanningToolSchemaFactory());
        for (ToolCallback callback : callbackAdapter.issuedCallbacks(context)) {
            schemas.put(callback.getToolDefinition().name(), objectMapper.readTree(callback.getToolDefinition().inputSchema()));
        }
        return Map.copyOf(schemas);
    }

    private static AgentPromptContext contextFor(List<CapabilityPolicy> policies) {
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());
        LinkedHashMap<CapabilityHandle, CapabilityPolicy> issuedCapabilities = new LinkedHashMap<>();
        int sequence = 1;
        for (CapabilityPolicy policy : policies) {
            issuedCapabilities.put(new CapabilityHandle("capability-" + sequence, binding), policy);
            sequence++;
        }
        return new AgentPromptContext("Find routes", SessionHistory.empty(), binding.runId(), binding.attemptId(),
                issuedCapabilities, Map.of(), Map.of(), Map.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static PlanningToolRegistry registry() {
        AgentCapabilityConfiguration configuration = new AgentCapabilityConfiguration();
        jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec payloadCodec =
                configuration.canonicalCapabilityPayloadCodec(validator);
        return configuration.planningToolRegistry(List.of(
                        configuration.corePlanningToolProvider(),
                        configuration.codeIntelligencePlanningToolProvider(mock(JavaSemanticServiceHttpAdapter.class), payloadCodec)),
                validator, payloadCodec);
    }

    private static List<String> required(Map<String, JsonNode> schemas, String toolName) {
        JsonNode schema = schemas.get(toolName);
        return schema.path("required").valueStream().map(jsonNode -> jsonNode.textValue()).toList();
    }
}
