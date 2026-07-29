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
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.handle.CandidateHandleRef;
import com.java.system.agent.runtime.domain.handle.EvidenceHandleRef;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
        assertThat(schemas).hasSize(7).containsKeys("agent_submit_answer", "agent_request_clarification");
        assertThat(required(schemas, "agent_submit_answer")).containsExactly("statements");
        assertThat(required(schemas, "agent_request_clarification"))
                .containsExactlyInAnyOrder("question", "candidateHandles", "reason");
        for (Map.Entry<String, JsonNode> entry : schemas.entrySet()) {
            if (!entry.getKey().startsWith("codebase_")) {
                continue;
            }
            JsonNode schema = entry.getValue();
            assertThat(schema.path("required")).extracting(JsonNode::asText).contains("candidateHandles");
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
        assertThat(statement.path("required")).extracting(JsonNode::asText)
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

        AgentActionProposal proposal = registry.interpretToolCall(new AssistantMessage.ToolCall("call-1", "function",
                policy.name(), """
                        {"candidateHandles":[" "],"questionToResolve":"Find the route","rationale":"Lookup the route","apiPath":"/orders"}
                        """), context);

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
    }

    @Test
    void mapsFixedPlanningToolsWithoutResolvingRawIssuedHandleReferences() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext context = contextFor(List.of());

        AgentActionProposal answerProposal = registry.interpretToolCall(new AssistantMessage.ToolCall(
                "call-1", "function", "agent_submit_answer", """
                        {"statements":[{"statementId":"statement-1","type":"FACT","text":"The route is called by checkout","claimId":"claim-1","citationHandles":["evidence-unknown"],"observationIds":["observation-1"]}]}
                        """), context);
        AgentActionProposal clarifyProposal = registry.interpretToolCall(new AssistantMessage.ToolCall(
                "call-2", "function", "agent_request_clarification", """
                        {"question":"Which repository?","candidateHandles":["candidate-2","candidate-1"],"reason":"The route scope is ambiguous"}
                        """), context);

        assertThat(answerProposal).isInstanceOf(AgentActionProposal.Proposed.class);
        AnswerAction answer = (AnswerAction) ((AgentActionProposal.Proposed) answerProposal).action();
        assertThat(answer.document().statements().getFirst().citations())
                .extracting(EvidenceHandleRef::value).containsExactly("evidence-unknown");
        assertThat(clarifyProposal).isInstanceOf(AgentActionProposal.Proposed.class);
        ClarifyAction clarify = (ClarifyAction) ((AgentActionProposal.Proposed) clarifyProposal).action();
        assertThat(clarify.candidates()).extracting(CandidateHandleRef::value)
                .containsExactly("candidate-2", "candidate-1");
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry) throws Exception {
        return schemasByToolName(registry, registry.availableCapabilities());
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry, List<CapabilityPolicy> policies) throws Exception {
        AgentPromptContext context = contextFor(policies);
        ObjectMapper objectMapper = new ObjectMapper();
        LinkedHashMap<String, JsonNode> schemas = new LinkedHashMap<>();
        for (ToolCallback callback : registry.issuedCallbacks(context)) {
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
                issuedCapabilities, Map.of(), Map.of(), Map.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0));
    }

    private static PlanningToolRegistry registry() {
        return new AgentCapabilityConfiguration().planningToolRegistry(
                mock(JavaSemanticServiceHttpAdapter.class), new ObjectMapper(),
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    private static List<String> required(Map<String, JsonNode> schemas, String toolName) {
        JsonNode schema = schemas.get(toolName);
        return schema.path("required").valueStream().map(JsonNode::textValue).toList();
    }
}
