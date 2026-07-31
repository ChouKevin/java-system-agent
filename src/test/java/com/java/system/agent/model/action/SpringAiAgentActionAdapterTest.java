package com.java.system.agent.model.action;

import com.google.genai.errors.ClientException;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.AnswerPlanningToolRegistration;
import com.java.system.agent.capability.planning.ClarifyPlanningToolRegistration;
import com.java.system.agent.capability.planning.ExecutePlanningToolRegistration;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.RequestClarificationPlanningInput;
import com.java.system.agent.capability.planning.RequestClarificationPlanningMapper;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningInput;
import com.java.system.agent.capability.planning.SubmitAnswerPlanningMapper;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.evidence.ArtifactRef;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ExecutionDeferral;
import com.java.system.agent.answering.domain.run.ExecutionDeferralReason;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentActionTransportException;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import jakarta.validation.Validation;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spring AI action adapter 的結構輸出與單次 transport 邊界測試
 */
class SpringAiAgentActionAdapterTest {

    @Test
    void mapsQueryAndPreservesModelCandidateOrderWithExactlyOneModelCall() {
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", """
                        {"candidateHandles":["candidate-2","candidate-1"],"questionToResolve":"Which route calls it?","rationale":"Trace callers"}
                        """)))
                .build());
        SpringAiAgentActionAdapter adapter = adapter(model);

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.capability().value()).isEqualTo("cap-1");
        assertThat(action.candidates()).extracting(candidateHandleReference -> candidateHandleReference.value()).containsExactly("candidate-2", "candidate-1");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsAnswerToolAndPreservesRawEvidenceReferenceValuesWithExactlyOneModelCall() {
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "agent_submit_answer", """
                        {"statements":[{"statementId":"statement-1","type":"FACT","text":"Checkout calls the route","claimId":"claim-1","citationHandles":["evidence-unknown"],"observationIds":["observation-1"]}]}
                        """)))
                .build());

        AgentActionProposal proposal = adapter(model).nextAction(answerContext());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        AnswerAction action = (AnswerAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.document().statements().getFirst().citations())
                .extracting(evidenceHandleReference -> evidenceHandleReference.value()).containsExactly("evidence-unknown");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsClarifyToolAndPreservesModelCandidateOrderWithExactlyOneModelCall() {
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "agent_request_clarification", """
                        {"question":"Which repository?","candidateHandles":["candidate-2","candidate-1"],"reason":"The route scope is ambiguous"}
                        """)))
                .build());

        AgentActionProposal proposal = adapter(model).nextAction(context());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        ClarifyAction action = (ClarifyAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.candidates()).extracting(candidateHandleReference -> candidateHandleReference.value()).containsExactly("candidate-2", "candidate-1");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsExecuteToolAndLogsExecuteActionType() {
        CountingChatModel model = new CountingChatModel(toolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","jsonBody":"{\\"status\\":\\"approved\\"}","rationale":"Preview the order update"}
                """));
        CapturingHandler handler = captureActionLogs();

        try {
            AgentActionProposal proposal = adapter(model).nextAction(context());

            assertThat(proposal).isEqualTo(new AgentActionProposal.Proposed(new ExecuteAction(
                    ExternalHttpMethod.POST,
                    "https://service.example/orders",
                    Optional.of("{\"status\":\"approved\"}"),
                    "Preview the order update")));
            assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION)
                    .contains("Emit a URL only as execute_http.targetUrl")
                    .doesNotContain("adapter name, URL, or retry instruction");
            assertThat(handler.records()).extracting(record -> record.getParameters()[3]).containsExactly("EXECUTE");
        } finally {
            releaseActionLogs(handler);
        }
    }

    @Test
    void keepsMalformedExecuteToolProposalLogActionTypeAsNone() {
        CountingChatModel model = new CountingChatModel(toolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","jsonBody":"{]","rationale":"Preview the order update"}
                """));
        CapturingHandler handler = captureActionLogs();

        try {
            AgentActionProposal proposal = adapter(model).nextAction(context());

            assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
            assertThat(handler.records()).extracting(record -> record.getParameters()[3]).containsExactly("NONE");
        } finally {
            releaseActionLogs(handler);
        }
    }

    @Test
    void mapsStrictToolInputFailureToSpecificMalformedWithoutCallingExecutor() {
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", """
                        {"candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","rationale":"Trace callers","unknown":"x"}
                        """)))
                .build());

        AgentActionProposal proposal = adapter(model).nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsNullFixedToolArgumentsToInvalidToolInputForNormalActionRejection() {
        CountingChatModel model = new CountingChatModel(toolCall("agent_request_clarification", null));

        AgentActionProposal proposal = adapter(model).nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsTypeAndDeclarativeToolInputFailuresToInvalidToolInput() {
        CountingChatModel unknownEnum = new CountingChatModel(toolCall("agent_submit_answer", """
                {"statements":[{"statementId":"statement-1","type":"UNKNOWN","text":"Checkout calls the route","citationHandles":[],"observationIds":[]}]}
                """));
        CountingChatModel blankQuestion = new CountingChatModel(toolCall("agent_request_clarification", """
                {"question":" ","candidateHandles":[],"reason":"The route scope is ambiguous"}
                """));

        AgentActionProposal enumProposal = adapter(unknownEnum).nextAction(answerContext());
        AgentActionProposal blankProposal = adapter(blankQuestion).nextAction(context());

        assertThat(enumProposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(blankProposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(unknownEnum.calls()).isEqualTo(1);
        assertThat(blankQuestion.calls()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidNestedAnswerStatementsBeforeExecutingAnswerMapper() {
        AtomicInteger mapperCalls = new AtomicInteger();
        CountingChatModel missingType = new CountingChatModel(toolCall("agent_submit_answer", """
                {"statements":[{"statementId":"statement-1","text":"Checkout calls the route","citationHandles":[],"observationIds":[]}]}
                """));
        CountingChatModel nullType = new CountingChatModel(toolCall("agent_submit_answer", """
                {"statements":[{"statementId":"statement-1","type":null,"text":"Checkout calls the route","citationHandles":[],"observationIds":[]}]}
                """));
        CountingChatModel blankCitation = new CountingChatModel(toolCall("agent_submit_answer", """
                {"statements":[{"statementId":"statement-1","type":"FACT","text":"Checkout calls the route","claimId":"claim-1","citationHandles":[" "],"observationIds":[]}]}
                """));

        AgentActionProposal missingTypeProposal = adapter(missingType, input -> failIfAnswerMapperExecutes(mapperCalls))
                .nextAction(answerContext());
        AgentActionProposal nullTypeProposal = adapter(nullType, input -> failIfAnswerMapperExecutes(mapperCalls))
                .nextAction(answerContext());
        AgentActionProposal blankCitationProposal = adapter(blankCitation,
                input -> failIfAnswerMapperExecutes(mapperCalls)).nextAction(answerContext());

        assertThat(missingTypeProposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(nullTypeProposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(blankCitationProposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
        assertThat(mapperCalls).hasValue(0);
        assertThat(missingType.calls()).isEqualTo(1);
        assertThat(nullType.calls()).isEqualTo(1);
        assertThat(blankCitation.calls()).isEqualTo(1);
    }

    @Test
    void mapsMixedTextAndToolCallToMalformedWithoutAnotherModelCall() {
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content("I will query it")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", """
                        {"candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","rationale":"Trace callers"}
                        """)))
                .build());

        AgentActionProposal proposal = adapter(model).nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void acceptsWhitespaceOnlyAssistantTextAlongsideExactlyOneIssuedToolCall() {
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content(" \n\t ")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", """
                        {"candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","rationale":"Trace callers"}
                        """)))
                .build());

        AgentActionProposal proposal = adapter(model).nextAction(context());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void extractsTheSingleToolNameAndRawArgumentsThroughTheNeutralRegistryProtocol() {
        String rawArguments = """
                {"candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","rationale":"Trace callers"}
                """;
        AgentPromptContext promptContext = context();
        PlanningToolRegistry registry = mock(PlanningToolRegistry.class);
        SpringAiPlanningToolCallbackAdapter callbacks = mock(SpringAiPlanningToolCallbackAdapter.class);
        when(callbacks.issuedCallbacks(promptContext)).thenReturn(List.of());
        AgentActionProposal expected = new AgentActionProposal.Malformed("INVALID_TOOL_INPUT");
        when(registry.interpretToolCall("callers", rawArguments, promptContext)).thenReturn(expected);
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content(" \n\t ")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", rawArguments)))
                .build());
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(
                ChatClient.builder(model).build(), registry, callbacks);

        AgentActionProposal proposal = adapter.nextAction(promptContext);

        assertThat(proposal).isEqualTo(expected);
        verify(registry).interpretToolCall(eq("callers"), eq(rawArguments), eq(promptContext));
    }

    @Test
    void rejectsNonblankAssistantTextBeforeCallingTheNeutralRegistryProtocol() {
        AgentPromptContext promptContext = context();
        PlanningToolRegistry registry = mock(PlanningToolRegistry.class);
        SpringAiPlanningToolCallbackAdapter callbacks = mock(SpringAiPlanningToolCallbackAdapter.class);
        when(callbacks.issuedCallbacks(promptContext)).thenReturn(List.of());
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content("I will query it")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", "{}")))
                .build());
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(
                ChatClient.builder(model).build(), registry, callbacks);

        AgentActionProposal proposal = adapter.nextAction(promptContext);

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        verifyNoInteractions(registry);
    }

    @Test
    void rejectsZeroOrMultipleToolCallsWithoutAnotherModelCall() {
        CountingChatModel zeroToolCalls = new CountingChatModel(AssistantMessage.builder().content("").build());
        CountingChatModel multipleToolCalls = new CountingChatModel(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "callers", "{}"),
                        new AssistantMessage.ToolCall("call-2", "function", "callers", "{}")))
                .build());

        AgentActionProposal zeroProposal = adapter(zeroToolCalls).nextAction(context());
        AgentActionProposal multipleProposal = adapter(multipleToolCalls).nextAction(context());

        assertThat(zeroProposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(multipleProposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(zeroToolCalls.calls()).isEqualTo(1);
        assertThat(multipleToolCalls.calls()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownOrUnissuedToolNamesWithoutAnotherModelCall() {
        CountingChatModel unknownTool = new CountingChatModel(toolCall("unknown_tool", "{}"));
        CountingChatModel unissuedTool = new CountingChatModel(toolCall("codebase_lookup_api_route", "{}"));

        AgentActionProposal unknownProposal = adapter(unknownTool).nextAction(context());
        AgentActionProposal unissuedProposal = adapter(unissuedTool).nextAction(context());

        assertThat(unknownProposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(unissuedProposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(unknownTool.calls()).isEqualTo(1);
        assertThat(unissuedTool.calls()).isEqualTo(1);
    }

    @Test
    void letsRegistryContractDefectsEscapeWithoutAnotherModelCall() {
        CountingChatModel model = new CountingChatModel(toolCall("callers", """
                {"candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","rationale":"Trace callers"}
                """));

        assertThatThrownBy(() -> contractDefectAdapter(model).nextAction(context()))
                .isInstanceOf(AgentActionContractException.class)
                .hasMessage("planning tool registry contract failed");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void rejectsTextOnlyResponseWithoutAnotherModelCall() {
        CountingChatModel model = new CountingChatModel("I need more context before selecting a planning tool");
        SpringAiAgentActionAdapter adapter = adapter(model);

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsConversionFailureToMalformedAndGeneralTransportToSanitizedUnavailable() {
        CountingChatModel malformedModel = new CountingChatModel("not json");
        SpringAiAgentActionAdapter malformedAdapter = adapter(malformedModel);
        CountingChatModel unavailableModel = new CountingChatModel(new IllegalStateException("provider response omitted"));
        SpringAiAgentActionAdapter unavailableAdapter = adapter(unavailableModel);

        AgentActionProposal malformed = malformedAdapter.nextAction(context());

        assertThat(malformed).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThatThrownBy(() -> unavailableAdapter.nextAction(context()))
                .isInstanceOf(AgentActionTransportException.class)
                .hasMessage("ACTION_MODEL_UNAVAILABLE");
        assertThat(malformedModel.calls()).isEqualTo(1);
        assertThat(unavailableModel.calls()).isEqualTo(1);
    }

    @Test
    void rendersActionContextInFixedOrderAndWithoutMemoryInstructions() {
        String prompt = new AgentActionPromptRenderer().render(context());

        assertThat(prompt.indexOf("Original question")).isLessThan(prompt.indexOf("Session turns"));
        assertThat(prompt.indexOf("Session turns")).isLessThan(prompt.indexOf("Capabilities"));
        assertThat(prompt.indexOf("Capabilities")).isLessThan(prompt.indexOf("Candidates"));
        assertThat(prompt.indexOf("Candidates")).isLessThan(prompt.indexOf("Evidence"));
        assertThat(prompt.indexOf("Evidence")).isLessThan(prompt.indexOf("Observations"));
        assertThat(prompt.indexOf("Observations")).isLessThan(prompt.indexOf("Latest rejection"));
        assertThat(prompt.indexOf("Latest rejection")).isLessThan(prompt.indexOf("Remaining budget"));
        assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION).contains("Use only issued opaque handles", "exactly one registered planning tool call")
                .doesNotContain("memory", "advisor");
    }

    @Test
    void classifiesRateLimitTransportFailureWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new ResourceExhaustedException());
        SpringAiAgentActionAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.nextAction(context()))
                .isInstanceOf(AgentActionTransportException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void preservesAnExternalExecutionDeferralWithoutTransportClassification() {
        ExternalExecutionDeferredException deferral = new ExternalExecutionDeferredException(
                new ExecutionDeferral(Instant.parse("2026-07-28T01:02:03Z"), ExecutionDeferralReason.RATE_LIMITED));
        CountingChatModel model = new CountingChatModel(deferral);
        SpringAiAgentActionAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.nextAction(context())).isSameAs(deferral);
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void classifiesWrappedGoogleGenAi429WithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new IllegalStateException(
                new ClientException(429, "RESOURCE_EXHAUSTED", "secret provider body")));
        SpringAiAgentActionAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.nextAction(context()))
                .isInstanceOf(AgentActionTransportException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void classifiesHttp429TransportMetadataWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        SpringAiAgentActionAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.nextAction(context()))
                .isInstanceOf(AgentActionTransportException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(model.calls()).isEqualTo(1);
    }

    private static SpringAiAgentActionAdapter adapter(CountingChatModel model) {
        return adapter(model, new SubmitAnswerPlanningMapper());
    }

    private static SpringAiAgentActionAdapter adapter(
            CountingChatModel model,
            Function<SubmitAnswerPlanningInput, AnswerAction> answerMapper) {
        return new SpringAiAgentActionAdapter(ChatClient.builder(model).build(), registry(new ToolInputMapper(), answerMapper));
    }

    private static SpringAiAgentActionAdapter contractDefectAdapter(CountingChatModel model) {
        return new SpringAiAgentActionAdapter(ChatClient.builder(model).build(), registry(input -> {
            throw new IllegalStateException("broken mapper");
        }));
    }

    private static PlanningToolRegistry registry(QueryPlanningMapper<ToolInput, ToolInput> mapper) {
        return registry(mapper, new SubmitAnswerPlanningMapper());
    }

    private static PlanningToolRegistry registry(
            QueryPlanningMapper<ToolInput, ToolInput> mapper,
            Function<SubmitAnswerPlanningInput, AnswerAction> answerMapper) {
        CapabilityPolicy policy = new CapabilityPolicy("callers", "v1", Set.of(CandidateKind.REPOSITORY), 1, 2);
        CapabilityPolicy unissuedPolicy = new CapabilityPolicy("codebase_lookup_api_route", "v1",
                Set.of(CandidateKind.REPOSITORY), 1, 2);
        CapabilityExecutor<ToolInput> executor = (context, input) ->
                new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        PlanningToolProvider provider = () -> List.of(PlanningToolRegistry.registration(
                policy, ToolInput.class, ToolInput.class, mapper, executor, payloadCodec),
                PlanningToolRegistry.registration(unissuedPolicy, ToolInput.class, ToolInput.class, mapper, executor,
                        payloadCodec),
                new AnswerPlanningToolRegistration<>("agent_submit_answer", SubmitAnswerPlanningInput.class,
                        answerMapper),
                new ClarifyPlanningToolRegistration<>("agent_request_clarification", RequestClarificationPlanningInput.class,
                        new RequestClarificationPlanningMapper()),
                new ExecutePlanningToolRegistration());
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(provider), new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator()),
                payloadCodec);
        return registry;
    }

    private static AnswerAction failIfAnswerMapperExecutes(AtomicInteger mapperCalls) {
        mapperCalls.incrementAndGet();
        throw new AssertionError("answer mapper must not execute for invalid planning input");
    }

    private static CapturingHandler captureActionLogs() {
        Logger logger = Logger.getLogger(SpringAiAgentActionAdapter.class.getName());
        boolean originalUseParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        CapturingHandler handler = new CapturingHandler(originalUseParentHandlers);
        logger.addHandler(handler);
        return handler;
    }

    private static void releaseActionLogs(CapturingHandler handler) {
        Logger logger = Logger.getLogger(SpringAiAgentActionAdapter.class.getName());
        logger.removeHandler(handler);
        logger.setUseParentHandlers(handler.originalUseParentHandlers());
    }

    private static AssistantMessage toolCall(String name, String arguments) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build();
    }

    private AgentPromptContext context() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RevisionVector revisions = RevisionVector.empty().pin(new RepositoryId("repo-1"), new RepositoryRevision("rev-1"));
        HandleBinding binding = new HandleBinding(runId, attemptId, revisions);
        CapabilityHandle capability = new CapabilityHandle("cap-1", binding);
        CandidateHandle firstCandidate = new CandidateHandle("candidate-1", binding, CandidateKind.REPOSITORY);
        CandidateHandle secondCandidate = new CandidateHandle("candidate-2", binding, CandidateKind.REPOSITORY);
        CapabilityPolicy descriptor = new CapabilityPolicy("callers", "v1", Set.of(CandidateKind.REPOSITORY), 1, 2);
        return new AgentPromptContext("Where is it called?", SessionHistory.empty(), runId, attemptId,
                Map.of(capability, descriptor),
                Map.of(firstCandidate, new IssuedCandidate(firstCandidate, new RepositoryCandidate(new RepositoryId("repo-1"), "first")),
                        secondCandidate, new IssuedCandidate(secondCandidate, new RepositoryCandidate(new RepositoryId("repo-2"), "second"))),
                Map.of(), Map.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private AgentPromptContext answerContext() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RevisionVector revisions = RevisionVector.empty().pin(new RepositoryId("repo-1"), new RepositoryRevision("rev-1"));
        HandleBinding binding = new HandleBinding(runId, attemptId, revisions);
        EvidenceHandle evidenceHandle = new EvidenceHandle("evidence-1", binding);
        EvidenceRef evidence = new EvidenceRef("semantic", new RepositoryId("repo-1"), new RepositoryRevision("rev-1"),
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Checkout#call", Optional.empty()), "Call evidence", List.of(),
                new ArtifactRef("digest-1"));
        ObservationId observationId = new ObservationId("observation-1");
        AgentObservation observation = new AgentObservation(observationId, ObservationSource.RUNTIME,
                ObservationCode.PARTIAL_GRAPH, "Graph is partial", Set.of(), Set.of(evidenceHandle), "runtime");
        return new AgentPromptContext("Where is it called?", SessionHistory.empty(), runId, attemptId, Map.of(), Map.of(),
                Map.of(evidenceHandle, new IssuedEvidence(evidenceHandle, evidence)), Map.of(observationId, observation),
                Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private record ToolInput(
            @JsonProperty(required = true) List<String> candidateHandles,
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale) {
    }

    private static final class ToolInputMapper implements QueryPlanningMapper<ToolInput, ToolInput> {

        @Override
        public QueryPlanningSelection<ToolInput> map(ToolInput input) {
            return new QueryPlanningSelection<>(input.candidateHandles().stream().map(CandidateHandleRef::new).toList(),
                    input.questionToResolve(), input.rationale(), input);
        }
    }
    private static final class CountingChatModel implements ChatModel {

        private final String response;
        private final Optional<AssistantMessage> assistantMessage;
        private final Optional<RuntimeException> failure;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingChatModel(String response) {
            this.response = response;
            this.assistantMessage = Optional.empty();
            this.failure = Optional.empty();
        }

        private CountingChatModel(AssistantMessage assistantMessage) {
            this.response = "";
            this.assistantMessage = Optional.of(assistantMessage);
            this.failure = Optional.empty();
        }

        private CountingChatModel(RuntimeException failure) {
            this.response = "";
            this.assistantMessage = Optional.empty();
            this.failure = Optional.of(failure);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return new ChatResponse(List.of(new Generation(assistantMessage.orElseGet(() -> new AssistantMessage(response)))));
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class ResourceExhaustedException extends RuntimeException {
        private ResourceExhaustedException() {
            super("provider response omitted");
        }
    }

    private static final class CapturingHandler extends Handler {

        private final boolean originalUseParentHandlers;
        private final List<LogRecord> records = new ArrayList<>();

        private CapturingHandler(boolean originalUseParentHandlers) {
            this.originalUseParentHandlers = originalUseParentHandlers;
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private boolean originalUseParentHandlers() {
            return originalUseParentHandlers;
        }

        private List<LogRecord> records() {
            return List.copyOf(records);
        }
    }
}
