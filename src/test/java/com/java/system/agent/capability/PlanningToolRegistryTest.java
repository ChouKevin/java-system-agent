package com.java.system.agent.capability;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.capability.planning.AnswerPlanningToolRegistration;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.ClarifyPlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.model.action.planning.RequestClarificationPlanningInput;
import com.java.system.agent.model.action.planning.RequestClarificationPlanningMapper;
import com.java.system.agent.model.action.planning.SubmitAnswerPlanningInput;
import com.java.system.agent.model.action.planning.SubmitAnswerPlanningMapper;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanningToolRegistry 的固定 action tool 與 issued catalog 邊界測試
 */
class PlanningToolRegistryTest {

    @Test
    void exposesFixedToolsOnEveryTurnAndRejectsUnknownOrUnissuedNamesAsMalformedResponses() {
        PlanningToolRegistry registry = registry();
        AgentPromptContext context = context();

        List<String> issuedNames = registry.issuedCallbacks(context).stream()
                .map(ToolCallback::getToolDefinition).map(definition -> definition.name()).toList();
        AgentActionProposal unknown = registry.interpretToolCall(toolCall("unknown_tool", "{}"), context);
        AgentActionProposal unissued = registry.interpretToolCall(toolCall("query_tool", "{}"), context);

        assertThat(issuedNames).containsExactlyInAnyOrder("agent_submit_answer", "agent_request_clarification");
        assertThat(unknown).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(unissued).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
    }

    @Test
    void mapsStrictFixedToolInputFailuresToInvalidToolInput() {
        PlanningToolRegistry registry = registry();

        AgentActionProposal proposal = registry.interpretToolCall(toolCall("agent_request_clarification", """
                {"question":"Which repository?","candidateHandles":[],"reason":"Scope is ambiguous","unknown":"value"}
                """), context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
    }

    private static PlanningToolRegistry registry() {
        ObjectMapper mapper = new ObjectMapper();
        PlanningToolSchemaFactory schemaFactory = new PlanningToolSchemaFactory(mapper);
        CapabilityPolicy policy = new CapabilityPolicy("query_tool", "v1", Set.of(CandidateKind.REPOSITORY), 0, 1);
        QueryPlanningMapper<TestInput, TestInput> queryMapper = input ->
                new QueryPlanningSelection<>(List.of(), input.questionToResolve(), input.rationale(), input);
        return new PlanningToolRegistry(List.of(
                PlanningToolRegistry.registration(policy, TestInput.class, TestInput.class, queryMapper,
                        (executionContext, input) -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                        schemaFactory),
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        new SubmitAnswerPlanningMapper(), schemaFactory),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification", RequestClarificationPlanningInput.class,
                        new RequestClarificationPlanningMapper(), schemaFactory)),
                new StrictPlanningToolDecoder(mapper, Validation.buildDefaultValidatorFactory().getValidator()),
                new CanonicalCapabilityPayloadCodec(mapper), schemaFactory);
    }

    private static AgentPromptContext context() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        return new AgentPromptContext("Find routes", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(), Map.of(),
                Map.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0));
    }

    private static AssistantMessage.ToolCall toolCall(String name, String arguments) {
        return new AssistantMessage.ToolCall("call-1", "function", name, arguments);
    }

    private record TestInput(
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale) {
    }
}
