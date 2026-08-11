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
import com.java.system.agent.model.prompt.AgentPromptResourceProperties;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import com.java.system.agent.model.prompt.PromptResourceCatalogLoader;
import com.java.system.agent.codeintelligence.semantic.JavaSemanticServiceHttpAdapter;
import com.java.system.agent.codeintelligence.CodeIntelligenceQuery;
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
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.NeedResolution;
import com.java.system.agent.answering.domain.plan.NeedResolutionStatus;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.DefaultResourceLoader;
import jakarta.validation.Validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.ModelInteraction;

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
        assertThat(schemas).hasSize(7).containsKeys("agent_submit_answer", "agent_request_clarification",
                CodeIntelligenceQuery.LIST_ENTRY_POINTS.capabilityName(),
                CodeIntelligenceQuery.LOOKUP_API_ROUTE.capabilityName(),
                CodeIntelligenceQuery.SUGGEST_API_ROUTE.capabilityName(),
                CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName(),
                CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName())
                .doesNotContainKeys(
                        CodeIntelligenceQuery.OUTGOING_CALL_GRAPH.capabilityName(),
                        CodeIntelligenceQuery.INCOMING_CALL_GRAPH.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_CONCEPTS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_EVENT_LISTENERS.capabilityName(),
                        CodeIntelligenceQuery.RESOLVE_CONCEPT.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_METHOD_IMPLEMENTATIONS.capabilityName(),
                        CodeIntelligenceQuery.DISCOVER_TYPE_MEMBERS.capabilityName(),
                        CodeIntelligenceQuery.FIND_INTERNAL_REFERENCES.capabilityName(),
                        CodeIntelligenceQuery.GET_EVIDENCE_SOURCE.capabilityName(),
                        CodeIntelligenceQuery.GET_SOURCE_SEGMENT.capabilityName());
        assertThat(required(schemas, "agent_submit_answer")).containsExactly("resolutions", "statements");
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
        assertThat(schemas.get(CodeIntelligenceQuery.GET_METHOD_SOURCE.capabilityName())
                .path("properties").has("boundTarget")).isFalse();
        assertThat(schemas.get(CodeIntelligenceQuery.RESOLVE_SOURCE_SYMBOL.capabilityName())
                .path("properties").has("boundContext")).isFalse();
    }

    @Test
    void catalogExposesEverySemanticQueryButNoRepositoryCatalogOperations() {
        PlanningToolRegistry registry = registry();

        assertThat(registry.availableCapabilities())
                .extracting(CapabilityPolicy::name)
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(CodeIntelligenceQuery.values())
                        .map(CodeIntelligenceQuery::capabilityName)
                        .toList())
                .doesNotContain("codebase_list_repositories", "codebase_get_repository");
        assertThat(registry.availableCapabilities())
                .allSatisfy(policy -> assertThat(policy.version()).isEqualTo(CodeIntelligenceQuery.LIST_ENTRY_POINTS.version()));
    }

    @Test
    void startupRegistryVerifiesTheNestedAnswerStatementSchemaContract() throws Exception {
        PlanningToolRegistry registry = registry();
        JsonNode answerSchema = schemasByToolName(registry).get("agent_submit_answer");
        JsonNode statement = answerSchema.path("properties").path("statements").path("items");
        JsonNode resolution = answerSchema.path("properties").path("resolutions").path("items");

        assertThat(answerSchema.path("required")).extracting(jsonNode -> jsonNode.asText())
                .containsExactly("resolutions", "statements");
        assertThat(statement.path("additionalProperties").asBoolean()).isFalse();
        assertThat(statement.path("required")).extracting(jsonNode -> jsonNode.asText())
                .containsExactlyInAnyOrder("statementId", "type", "text", "citationHandles", "observationIds");
        assertThat(statement.path("properties").path("text").path("minLength").asInt()).isEqualTo(1);
        assertThat(statement.path("properties").path("citationHandles").path("items").path("minLength").asInt())
                .isEqualTo(1);
        assertThat(resolution.path("additionalProperties").asBoolean()).isFalse();
        assertThat(resolution.path("required")).extracting(jsonNode -> jsonNode.asText())
                .containsExactlyInAnyOrder("needId", "status", "evidenceHandles", "observationIds");
        assertThat(resolution.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("needId", "status", "evidenceHandles", "observationIds");
        assertThat(resolution.path("properties").path("needId").path("minLength").asInt()).isEqualTo(1);
        assertThat(resolution.path("properties").path("evidenceHandles").path("items").path("minLength").asInt())
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

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=codebase_lookup_api_route; reason=BEAN_VALIDATION; "
                        + "invalidFields=[candidateHandles]; constraints=[candidateHandles:NotBlank]"));
    }

    @Test
    void mapsFixedPlanningToolsWithoutResolvingRawIssuedHandleReferences() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext context = contextFor(List.of());

        AgentActionProposal answerProposal = registry.interpretToolCall("agent_submit_answer", """
                        {"statements":[{"statementId":"statement-1","type":"FACT","text":"The route is called by checkout","claimId":"claim-1","citationHandles":["evidence-unknown"],"observationIds":["observation-1"]}],"resolutions":[{"needId":"need-2","status":"SUPPORTED","evidenceHandles":["evidence-unknown"],"observationIds":[]},{"needId":"need-1","status":"UNAVAILABLE","evidenceHandles":[],"observationIds":["observation-1"]}]}
                        """, context);
        AgentActionProposal clarifyProposal = registry.interpretToolCall("agent_request_clarification", """
                        {"question":"Which repository?","candidateHandles":["candidate-2","candidate-1"],"reason":"The route scope is ambiguous"}
                        """, context);

        assertThat(answerProposal).isInstanceOf(AgentActionProposal.Proposed.class);
        AnswerAction answer = (AnswerAction) ((AgentActionProposal.Proposed) answerProposal).action();
        assertThat(answer.document().statements().getFirst().citations())
                .extracting(evidenceHandleReference -> evidenceHandleReference.value()).containsExactly("evidence-unknown");
        assertThat(answer.resolutions()).extracting(NeedResolution::needId)
                .extracting(InformationNeedId::value)
                .containsExactly("need-2", "need-1");
        assertThat(answer.resolutions()).extracting(NeedResolution::status)
                .containsExactly(NeedResolutionStatus.SUPPORTED, NeedResolutionStatus.UNAVAILABLE);
        assertThat(answer.resolutions().getFirst().evidence())
                .extracting(evidenceHandleReference -> evidenceHandleReference.value())
                .containsExactly("evidence-unknown");
        assertThat(answer.resolutions().get(1).observations())
                .extracting(observationId -> observationId.value())
                .containsExactly("observation-1");
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

    @Test
    void exposesTheCanonicalPlanActionAndSchemaBeforeTheDurablePlanExists() throws Exception {
        PlanningToolRegistry registry = registry();
        Map<String, JsonNode> schemas = schemasByToolName(registry, planningContext());
        AgentActionProposal proposal = registry.interpretToolCall("agent_plan_question", """
                        {"needs":[{"id":"scope","description":"Confirm the answer scope"}]}
                        """, planningContext());

        assertThat(registry.registrations()).extracting(registration -> registration.name())
                .containsOnlyOnce("agent_plan_question");
        assertThat(schemas).containsOnlyKeys("agent_plan_question");
        JsonNode needs = schemas.get("agent_plan_question").path("properties").path("needs");
        assertThat(needs.path("minItems").asInt()).isEqualTo(1);
        assertThat(needs.path("items").path("additionalProperties").asBoolean()).isFalse();
        assertThat(needs.path("items").path("required")).extracting(jsonNode -> jsonNode.asText())
                .containsExactlyInAnyOrder("id", "description");
        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        assertThat(((PlanAction) ((AgentActionProposal.Proposed) proposal).action()).plan().needs())
                .extracting(need -> need.id().value())
                .containsExactly("scope");
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry) throws Exception {
        return schemasByToolName(registry, registry.availableCapabilities());
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry, List<CapabilityPolicy> policies) throws Exception {
        return schemasByToolName(registry, contextFor(policies));
    }

    private static Map<String, JsonNode> schemasByToolName(PlanningToolRegistry registry, AgentPromptContext context) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LinkedHashMap<String, JsonNode> schemas = new LinkedHashMap<>();
        SpringAiPlanningToolCallbackAdapter callbackAdapter = new SpringAiPlanningToolCallbackAdapter(
                registry, new SpringAiPlanningToolSchemaFactory(), promptCatalog(registry));
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
        QuestionPlan plan = new QuestionPlan(List.of(
                new InformationNeed(new InformationNeedId("need-1"), "確認業務範圍"),
                new InformationNeed(new InformationNeedId("need-2"), "確認路由證據")));
        return new AgentPromptContext("Find routes", SessionHistory.empty(), binding.runId(), binding.attemptId(),
                issuedCapabilities, Map.of(), Map.of(), Map.of(), List.of(
                new ModelInteraction.ActionSelected(binding.attemptId(), new PlanAction(plan)),
                new ModelInteraction.ActionResultRecorded(
                        binding.attemptId(), new ActionResult.QuestionPlanRecorded(plan))), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private static AgentPromptContext planningContext() {
        HandleBinding binding = new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId("attempt-1"), RevisionVector.empty());
        return new AgentPromptContext("Find routes", SessionHistory.empty(), binding.runId(), binding.attemptId(),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Optional.empty(),
                new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
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

    private static PromptResourceCatalog promptCatalog(PlanningToolRegistry registry) {
        return new PromptResourceCatalogLoader(new DefaultResourceLoader()).load(
                promptResourceProperties(), registry);
    }

    private static AgentPromptResourceProperties promptResourceProperties() {
        return new AgentPromptResourceProperties(
                "classpath:/prompts/action/system.md",
                "classpath:/prompts/action/context.st",
                "classpath:/prompts/action/latest-answer-feedback.st",
                "classpath:/prompts/verification/system.md",
                "classpath:/prompts/verification/context.st",
                "classpath:/prompts/tools/");
    }

    private static List<String> required(Map<String, JsonNode> schemas, String toolName) {
        JsonNode schema = schemas.get(toolName);
        return schema.path("required").valueStream().map(jsonNode -> jsonNode.textValue()).toList();
    }
}
