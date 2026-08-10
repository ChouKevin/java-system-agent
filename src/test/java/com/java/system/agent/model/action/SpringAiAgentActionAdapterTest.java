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
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.ClaimId;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
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
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ExecutionDeferral;
import com.java.system.agent.answering.domain.run.ExecutionDeferralReason;
import com.java.system.agent.answering.domain.run.ModelInteraction;
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
import com.java.system.agent.model.prompt.AgentPromptResourceProperties;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import com.java.system.agent.model.prompt.PromptResourceCatalogLoader;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import jakarta.validation.Validation;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.text.MessageFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spring AI action adapter 的結構輸出與單次 transport 邊界測試
 */
class SpringAiAgentActionAdapterTest {

    @Test
    void logsContentSafeQueryFingerprintAndPriorSelectionWithoutChangingTheSinglePromptOrModelCall() {
        AnalysisAttemptId priorAttemptId = new AnalysisAttemptId("attempt-0");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AgentPromptContext promptContext = contextWithInteractions(List.of(
                new ModelInteraction.ActionSelected(priorAttemptId, new QueryAction(
                        new CapabilityHandle("cap-1", binding("attempt-0", "rev-1")),
                        List.of(new CandidateHandleRef("candidate-1")), "Changed question secret",
                        new CapabilityInputPayload("{\"candidateHandles\":[\"candidate-1\"]}"),
                        "Prior attempt rationale secret")),
                new ModelInteraction.ActionResultRecorded(priorAttemptId,
                        new ActionResult.QuerySucceeded(List.of(), List.of(), List.of())),
                new ModelInteraction.ActionSelected(attemptId, new QueryAction(capability(),
                        List.of(new CandidateHandleRef("candidate-1")), "Previous question secret",
                        new CapabilityInputPayload("{\"candidateHandles\":[\"candidate-1\"]}"),
                        "Previous rationale secret")),
                new ModelInteraction.ActionResultRecorded(attemptId,
                        new ActionResult.QuerySucceeded(List.of(), List.of(), List.of()))));
        AgentActionPromptRenderer renderer = mock(AgentActionPromptRenderer.class);
        String renderedPrompt = "PROMPT_SECRET apiKey=TOKEN_SECRET source=evidence secret";
        when(renderer.render(promptContext, List.of("callers"))).thenReturn(renderedPrompt);
        CountingChatModel model = new CountingChatModel(toolCall("callers", """
                {"candidateHandles":["candidate-1"],"questionToResolve":"Changed question secret","rationale":"Changed rationale secret"}
                """));
        SpringAiAgentActionAdapter adapter = fingerprintAdapter(model, renderer);
        CapturingHandler handler = captureActionLogs();

        try {
            AgentActionProposal proposal = adapter.nextAction(promptContext);

            assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
            assertThat(model.calls()).isEqualTo(1);
            assertThat(model.lastPrompt().orElseThrow().getUserMessage().getText()).isEqualTo(renderedPrompt);
            verify(renderer, times(1)).render(promptContext, List.of("callers"));
            List<String> logMessages = formattedMessages(handler);
            assertThat(logMessages).hasSize(1);
            assertThat(logMessages.getFirst())
                    .contains("promptCharacterCount=" + renderedPrompt.length(), "promptSha256=", "interactionCount=4")
                    .contains("remainingAgentSteps=3", "remainingQueryExecutions=3", "remainingExecuteExecutions=1")
                    .contains("remainingActionRejections=3", "resultCategory=PROPOSED", "actionType=QUERY")
                    .contains("actionFingerprint=", "executionPayloadFingerprint=",
                            "priorIdenticalCurrentAttemptSelectionCount=0",
                            "priorEquivalentCurrentAttemptPayloadSelectionCount=1", "elapsedMs=")
                    .doesNotContain("PROMPT_SECRET", "TOKEN_SECRET", "source=evidence secret", "Previous question secret",
                            "Previous rationale secret", "Prior attempt rationale secret", "Changed question secret",
                            "Changed rationale secret", "candidate-1");
        } finally {
            releaseActionLogs(handler);
        }
    }

    @Test
    void renders_and_sends_the_same_issued_tool_snapshot_once() {
        AgentPromptContext promptContext = context();
        PlanningToolRegistry registry = mock(PlanningToolRegistry.class);
        SpringAiPlanningToolCallbackAdapter callbackAdapter = mock(SpringAiPlanningToolCallbackAdapter.class);
        AgentActionPromptRenderer renderer = mock(AgentActionPromptRenderer.class);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("issued_callers");
        IssuedPlanningTools issued = new IssuedPlanningTools(List.of("issued_callers"), List.of(callback));
        when(callbackAdapter.issuedTools(promptContext)).thenReturn(issued);
        when(renderer.render(promptContext, issued.names())).thenReturn("snapshot-rendered-context");
        AgentActionProposal expected = new AgentActionProposal.Malformed("INVALID_TOOL_INPUT");
        when(registry.interpretToolCall("issued_callers", "{}", promptContext)).thenReturn(expected);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClientResponse response = new ChatClientResponse(new ChatResponse(List.of(new Generation(
                toolCall("issued_callers", "{}")))), Map.of());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(issued.callbacks())).thenReturn(requestSpec);
        when(requestSpec.system("resource system")).thenReturn(requestSpec);
        when(requestSpec.user("snapshot-rendered-context")).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatClientResponse()).thenReturn(response);
        PromptResourceCatalog catalog = stubPromptCatalog();
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(chatClient, registry,
                callbackAdapter, renderer, catalog);

        AgentActionProposal proposal = adapter.nextAction(promptContext);

        assertThat(proposal).isEqualTo(expected);
        verify(callbackAdapter, times(1)).issuedTools(promptContext);
        verify(renderer, times(1)).render(promptContext, issued.names());
        verify(requestSpec).toolCallbacks(issued.callbacks());
        verify(requestSpec, times(1)).call();
        verify(registry).interpretToolCall("issued_callers", "{}", promptContext);
    }

    @Test
    void fingerprintsAllActionTypesBySemanticFieldsOnly() {
        QueryAction query = new QueryAction(capability(), List.of(new CandidateHandleRef("candidate-1")),
                "Original question", new CapabilityInputPayload("{\"candidateHandles\":[\"candidate-1\"]}"),
                "Original rationale");
        QueryAction queryWithChangedProse = new QueryAction(capability(), List.of(new CandidateHandleRef("candidate-1")),
                "Changed question", new CapabilityInputPayload("{\"candidateHandles\":[\"candidate-1\"]}"),
                "Changed rationale");
        QueryAction queryWithChangedRationale = new QueryAction(
                capability(), List.of(new CandidateHandleRef("candidate-1")), "Original question",
                new CapabilityInputPayload("{\"candidateHandles\":[\"candidate-1\"]}"), "Changed rationale");
        QueryAction queryWithChangedPayload = new QueryAction(capability(), List.of(new CandidateHandleRef("candidate-1")),
                "Original question", new CapabilityInputPayload("{\"candidateHandles\":[\"candidate-2\"]}"),
                "Original rationale");
        QueryAction queryWithChangedCapability = new QueryAction(
                new CapabilityHandle("cap-2", capability().binding()), List.of(new CandidateHandleRef("candidate-1")),
                "Original question", new CapabilityInputPayload("{\"candidateHandles\":[\"candidate-1\"]}"),
                "Original rationale");
        ExecuteAction execute = new ExecuteAction(ExternalHttpMethod.POST, "https://service.example/orders",
                Optional.of("{\"status\":\"approved\"}"), "Original rationale");
        ExecuteAction executeWithChangedRationale = new ExecuteAction(ExternalHttpMethod.POST,
                "https://service.example/orders", Optional.of("{\"status\":\"approved\"}"), "Changed rationale");
        ExecuteAction executeWithChangedBody = new ExecuteAction(ExternalHttpMethod.POST, "https://service.example/orders",
                Optional.of("{\"status\":\"rejected\"}"), "Original rationale");
        AnswerAction answer = new AnswerAction(answerDocument(Set.of(new EvidenceHandleRef("evidence-b"),
                new EvidenceHandleRef("evidence-a")), Set.of(new ObservationId("observation-b"),
                new ObservationId("observation-a")), "Checkout calls the route"));
        AnswerAction answerWithReorderedReferences = new AnswerAction(answerDocument(Set.of(new EvidenceHandleRef("evidence-a"),
                new EvidenceHandleRef("evidence-b")), Set.of(new ObservationId("observation-a"),
                new ObservationId("observation-b")), "Checkout calls the route"));
        AnswerAction answerWithChangedStatement = new AnswerAction(answerDocument(Set.of(new EvidenceHandleRef("evidence-a"),
                new EvidenceHandleRef("evidence-b")), Set.of(new ObservationId("observation-a"),
                new ObservationId("observation-b")), "Checkout does not call the route"));
        ClarifyAction clarify = new ClarifyAction("Which repository?", List.of(new CandidateHandleRef("candidate-1")),
                "Original reason");
        ClarifyAction clarifyWithChangedReason = new ClarifyAction("Which repository?",
                List.of(new CandidateHandleRef("candidate-1")), "Changed reason");
        ClarifyAction clarifyWithChangedQuestion = new ClarifyAction("Which branch?",
                List.of(new CandidateHandleRef("candidate-1")), "Original reason");

        assertThat(fingerprint(query)).isEqualTo(fingerprint(queryWithChangedRationale))
                .isNotEqualTo(fingerprint(queryWithChangedProse))
                .isNotEqualTo(fingerprint(queryWithChangedPayload))
                .isNotEqualTo(fingerprint(queryWithChangedCapability));
        assertThat(payloadFingerprint(query)).isEqualTo(payloadFingerprint(queryWithChangedProse))
                .isNotEqualTo(payloadFingerprint(queryWithChangedPayload));
        assertThat(fingerprint(execute)).isEqualTo(fingerprint(executeWithChangedRationale))
                .isNotEqualTo(fingerprint(executeWithChangedBody));
        assertThat(fingerprint(answer)).isEqualTo(fingerprint(answerWithReorderedReferences))
                .isNotEqualTo(fingerprint(answerWithChangedStatement));
        assertThat(fingerprint(clarify)).isEqualTo(fingerprint(clarifyWithChangedReason))
                .isNotEqualTo(fingerprint(clarifyWithChangedQuestion));

        HandleBinding originalBinding = binding("attempt-1", "rev-1");
        HandleBinding reissuedBinding = binding("attempt-1", "rev-2");
        QueryAction originalRevisionQuery = new QueryAction(
                new CapabilityHandle("attempt-1:C1", originalBinding),
                List.of(new CandidateHandleRef("attempt-1:R1")), "Original question",
                new CapabilityInputPayload("{\"candidateHandles\":[\"repository\"]}"), "Original rationale");
        QueryAction reissuedRevisionQuery = new QueryAction(
                new CapabilityHandle("attempt-1:C1", reissuedBinding),
                List.of(new CandidateHandleRef("attempt-1:R1")), "Original question",
                new CapabilityInputPayload("{\"candidateHandles\":[\"repository\"]}"), "Original rationale");
        assertThat(fingerprint(originalRevisionQuery)).isEqualTo(fingerprint(reissuedRevisionQuery));
    }

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
    void mapsExecuteToolAndLogsOnlyBoundedExecuteMetadata() {
        CountingChatModel model = new CountingChatModel(toolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders?token=URL_SECRET","jsonBody":"{\\"credential\\":\\"BODY_SECRET\\"}","rationale":"EXECUTE_RATIONALE_SECRET"}
                """));
        SpringAiAgentActionAdapter adapter = adapter(model);
        CapturingHandler handler = captureActionLogs();

        try {
            AgentActionProposal proposal = adapter.nextAction(context());

            assertThat(proposal).isEqualTo(new AgentActionProposal.Proposed(new ExecuteAction(
                    ExternalHttpMethod.POST,
                    "https://service.example/orders?token=URL_SECRET",
                    Optional.of("{\"credential\":\"BODY_SECRET\"}"),
                    "EXECUTE_RATIONALE_SECRET")));
            List<String> logMessages = formattedMessages(handler);
            assertThat(logMessages).hasSize(1);
            assertThat(logMessages.getFirst()).contains("resultCategory=PROPOSED", "actionType=EXECUTE",
                            "actionFingerprint=", "catalogSha256=")
                    .doesNotContain("URL_SECRET", "BODY_SECRET", "EXECUTE_RATIONALE_SECRET");
        } finally {
            releaseActionLogs(handler);
        }
    }

    @Test
    void keepsMalformedExecuteToolProposalLogActionTypeAsNone() {
        CountingChatModel model = new CountingChatModel(toolCall("execute_http", """
                {"method":"POST","targetUrl":"https://service.example/orders","jsonBody":"{]","rationale":"Preview the order update"}
                """));
        SpringAiAgentActionAdapter adapter = adapter(model);
        CapturingHandler handler = captureActionLogs();

        try {
            AgentActionProposal proposal = adapter.nextAction(context());

            assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("INVALID_TOOL_INPUT"));
            assertThat(formattedMessages(handler)).allSatisfy(message -> assertThat(message)
                    .contains("resultCategory=MALFORMED", "malformedReason=INVALID_TOOL_INPUT",
                            "actionType=NONE", "actionFingerprint=NONE"));
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

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=callers; reason=JSON_CONTRACT"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsNullFixedToolArgumentsToInvalidToolInputForNormalActionRejection() {
        CountingChatModel model = new CountingChatModel(toolCall("agent_request_clarification", null));

        AgentActionProposal proposal = adapter(model).nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_request_clarification; reason=ABSENT_INPUT"));
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

        assertThat(enumProposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_submit_answer; reason=JSON_CONTRACT"));
        assertThat(blankProposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_request_clarification; reason=BEAN_VALIDATION; "
                        + "invalidFields=[question]; constraints=[question:NotBlank]"));
        assertThat(unknownEnum.calls()).isEqualTo(1);
        assertThat(blankQuestion.calls()).isEqualTo(1);
    }

    @Test
    void returns_actionable_safe_array_feedback_without_calling_answer_mapper() {
        AtomicInteger mapperCalls = new AtomicInteger();
        CountingChatModel model = new CountingChatModel(toolCall("agent_submit_answer", """
                {"statements":[{"statementId":"statement-1","type":"LIMITATION","text":"The source remains unresolved",\
                "citationHandles":[],"observationIds":"SENSITIVE_VALUE"}]}
                """));

        AgentActionProposal proposal = adapter(model, input -> failIfAnswerMapperExecutes(mapperCalls))
                .nextAction(answerContext());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_submit_answer; reason=JSON_CONTRACT; "
                        + "invalidField=statements.observationIds; expectedJsonType=array"));
        assertThat(proposal.toString()).doesNotContain("SENSITIVE_VALUE").doesNotContain("Set");
        assertThat(model.calls()).isEqualTo(1);
        assertThat(mapperCalls).hasValue(0);
    }

    @Test
    void returnsActionableFeedbackWhenAnswerStatementFieldsViolateTheirCrossFieldContract() {
        CountingChatModel nonFactClaim = new CountingChatModel(toolCall("agent_submit_answer", """
                {"statements":[{"statementId":"statement-1","type":"LIMITATION","text":"The source remains unresolved",\
                "claimId":"SENSITIVE_CLAIM","citationHandles":[],"observationIds":["observation-1"]}]}
                """));
        CountingChatModel incompleteFact = new CountingChatModel(toolCall("agent_submit_answer", """
                {"statements":[{"statementId":"statement-1","type":"FACT","text":"Checkout calls the route",\
                "citationHandles":[],"observationIds":["observation-1"]}]}
                """));

        AgentActionProposal nonFactProposal = adapter(nonFactClaim).nextAction(answerContext());
        AgentActionProposal factProposal = adapter(incompleteFact).nextAction(answerContext());

        assertThat(nonFactProposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_submit_answer; reason=ANSWER_CONTRACT; "
                        + "invalidFields=[statements.claimId]; constraints=[statements.claimId:AbsentForNonFact]"));
        assertThat(factProposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_submit_answer; reason=ANSWER_CONTRACT; "
                        + "invalidFields=[statements.claimId, statements.citationHandles]; "
                        + "constraints=[statements.claimId:RequiredForFact, "
                        + "statements.citationHandles:NotEmptyForFact]"));
        assertThat(nonFactProposal.toString()).doesNotContain("SENSITIVE_CLAIM");
        assertThat(nonFactClaim.calls()).isEqualTo(1);
        assertThat(incompleteFact.calls()).isEqualTo(1);
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

        assertThat(missingTypeProposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_submit_answer; reason=JSON_CONTRACT"));
        assertThat(nullTypeProposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_submit_answer; reason=EXPLICIT_NULL"));
        assertThat(blankCitationProposal).isEqualTo(new AgentActionProposal.Malformed(
                "INVALID_TOOL_INPUT: tool=agent_submit_answer; reason=BEAN_VALIDATION; "
                        + "invalidFields=[statements.citationHandles]; "
                        + "constraints=[statements.citationHandles:NotBlank]"));
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

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: actualToolCallCount=1; assistantTextPresent=true"));
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
        when(callbacks.issuedTools(promptContext)).thenReturn(new IssuedPlanningTools(List.of(), List.of()));
        AgentActionProposal expected = new AgentActionProposal.Malformed("INVALID_TOOL_INPUT");
        when(registry.interpretToolCall("callers", rawArguments, promptContext)).thenReturn(expected);
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content(" \n\t ")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", rawArguments)))
                .build());
        PromptResourceCatalog catalog = stubPromptCatalog();
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(
                ChatClient.builder(model).build(), registry, callbacks, new AgentActionPromptRenderer(catalog), catalog);

        AgentActionProposal proposal = adapter.nextAction(promptContext);

        assertThat(proposal).isEqualTo(expected);
        verify(registry).interpretToolCall(eq("callers"), eq(rawArguments), eq(promptContext));
    }

    @Test
    void rejectsNonblankAssistantTextBeforeCallingTheNeutralRegistryProtocol() {
        AgentPromptContext promptContext = context();
        PlanningToolRegistry registry = mock(PlanningToolRegistry.class);
        SpringAiPlanningToolCallbackAdapter callbacks = mock(SpringAiPlanningToolCallbackAdapter.class);
        when(callbacks.issuedTools(promptContext)).thenReturn(new IssuedPlanningTools(List.of(), List.of()));
        CountingChatModel model = new CountingChatModel(AssistantMessage.builder()
                .content("I will query it")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "callers", "{}")))
                .build());
        PromptResourceCatalog catalog = stubPromptCatalog();
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(
                ChatClient.builder(model).build(), registry, callbacks, new AgentActionPromptRenderer(catalog), catalog);

        AgentActionProposal proposal = adapter.nextAction(promptContext);

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: actualToolCallCount=1; assistantTextPresent=true"));
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

        assertThat(zeroProposal).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: actualToolCallCount=0; assistantTextPresent=false"));
        assertThat(multipleProposal).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: actualToolCallCount=2; assistantTextPresent=false"));
        assertThat(zeroToolCalls.calls()).isEqualTo(1);
        assertThat(multipleToolCalls.calls()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownOrUnissuedToolNamesWithoutAnotherModelCall() {
        CountingChatModel unknownTool = new CountingChatModel(toolCall("unknown_tool", "{}"));
        CountingChatModel unissuedTool = new CountingChatModel(toolCall("codebase_lookup_api_route", "{}"));

        AgentActionProposal unknownProposal = adapter(unknownTool).nextAction(context());
        AgentActionProposal unissuedProposal = adapter(unissuedTool).nextAction(context());

        assertThat(unknownProposal).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: toolStatus=UNKNOWN; expected=currentlyIssuedTool"));
        assertThat(unknownProposal.toString()).doesNotContain("unknown_tool");
        assertThat(unissuedProposal).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: requestedTool=codebase_lookup_api_route; "
                        + "toolStatus=NOT_CURRENTLY_ISSUED; expected=currentlyIssuedTool"));
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

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed(
                "MALFORMED_ACTION_RESPONSE: actualToolCallCount=0; assistantTextPresent=true"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsConversionFailureToMalformedAndGeneralTransportToSanitizedUnavailable() {
        CountingChatModel malformedModel = new CountingChatModel("not json");
        SpringAiAgentActionAdapter malformedAdapter = adapter(malformedModel);
        CountingChatModel unavailableModel = new CountingChatModel(new IllegalStateException("provider response omitted"));
        SpringAiAgentActionAdapter unavailableAdapter = adapter(unavailableModel);
        CapturingHandler handler = captureActionLogs();

        try {
            AgentActionProposal malformed = malformedAdapter.nextAction(context());

            assertThat(malformed).isEqualTo(new AgentActionProposal.Malformed(
                    "MALFORMED_ACTION_RESPONSE: actualToolCallCount=0; assistantTextPresent=true"));
            assertThatThrownBy(() -> unavailableAdapter.nextAction(context()))
                    .isInstanceOf(AgentActionTransportException.class)
                    .hasMessage("ACTION_MODEL_UNAVAILABLE");
            assertThat(malformedModel.calls()).isEqualTo(1);
            assertThat(unavailableModel.calls()).isEqualTo(1);
            assertThat(formattedMessages(handler)).allSatisfy(message -> assertThat(message)
                    .contains("actionFingerprint=NONE", "executionPayloadFingerprint=NONE",
                            "priorIdenticalCurrentAttemptSelectionCount=0",
                            "priorEquivalentCurrentAttemptPayloadSelectionCount=0")
                    .doesNotContain("provider response omitted"));
        } finally {
            releaseActionLogs(handler);
        }
    }

    @Test
    void sends_one_nonblank_resource_backed_request_with_the_currently_issued_callback_schemas() {
        CountingChatModel model = new CountingChatModel(toolCall("callers", """
                {"candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","rationale":"Trace callers"}
                """));
        PlanningToolRegistry registry = registry(new ToolInputMapper());
        PromptResourceCatalog catalog = promptCatalog(registry);
        SpringAiPlanningToolCallbackAdapter callbacks = new SpringAiPlanningToolCallbackAdapter(registry,
                new SpringAiPlanningToolSchemaFactory(), catalog);
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build(), registry,
                callbacks, new AgentActionPromptRenderer(catalog), catalog);
        IssuedPlanningTools issued = callbacks.issuedTools(context());

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        assertThat(model.calls()).isEqualTo(1);
        Prompt prompt = model.lastPrompt().orElseThrow();
        assertThat(prompt.getSystemMessage().getText()).isNotBlank();
        assertThat(prompt.getUserMessage().getText()).isNotBlank();
        assertThat(issued.callbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("callers", "agent_submit_answer", "agent_request_clarification", "execute_http");
        assertThat(issued.callbacks())
                .allSatisfy(callback -> assertThat(callback.getToolDefinition().inputSchema()).isNotBlank());
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
        PlanningToolRegistry registry = registry(new ToolInputMapper(), answerMapper);
        return new SpringAiAgentActionAdapter(ChatClient.builder(model).build(), registry, promptCatalog(registry));
    }

    private static SpringAiAgentActionAdapter fingerprintAdapter(
            CountingChatModel model,
            AgentActionPromptRenderer renderer) {
        PlanningToolRegistry registry = fingerprintRegistry();
        PromptResourceCatalog catalog = promptCatalog(registry);
        return new SpringAiAgentActionAdapter(ChatClient.builder(model).build(), registry,
                new SpringAiPlanningToolCallbackAdapter(registry, new SpringAiPlanningToolSchemaFactory(),
                        catalog), renderer, catalog);
    }

    private static SpringAiAgentActionAdapter contractDefectAdapter(CountingChatModel model) {
        PlanningToolRegistry registry = registry(input -> {
            throw new IllegalStateException("broken mapper");
        });
        return new SpringAiAgentActionAdapter(ChatClient.builder(model).build(), registry, promptCatalog(registry));
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

    private static PlanningToolRegistry fingerprintRegistry() {
        CapabilityPolicy policy = new CapabilityPolicy("callers", "v1", Set.of(CandidateKind.REPOSITORY), 1, 2);
        CapabilityExecutor<FingerprintExecutionInput> executor = (context, input) ->
                new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(
                Validation.buildDefaultValidatorFactory().getValidator());
        QueryPlanningMapper<ToolInput, FingerprintExecutionInput> mapper = input -> new QueryPlanningSelection<>(
                input.candidateHandles().stream().map(CandidateHandleRef::new).toList(), input.questionToResolve(),
                input.rationale(), new FingerprintExecutionInput(input.candidateHandles()));
        PlanningToolProvider provider = () -> List.of(PlanningToolRegistry.registration(
                policy, ToolInput.class, FingerprintExecutionInput.class, mapper, executor, payloadCodec));
        return new PlanningToolRegistry(List.of(provider), new StrictPlanningToolDecoder(
                Validation.buildDefaultValidatorFactory().getValidator()), payloadCodec);
    }

    private static PromptResourceCatalog promptCatalog(PlanningToolRegistry registry) {
        AgentPromptResourceProperties properties = new AgentPromptResourceProperties(
                "classpath:/prompts/action/system.md", "classpath:/prompts/action/context.st",
                "classpath:/prompts/action/latest-answer-feedback.st",
                "classpath:/prompts/verification/system.md", "classpath:/prompts/verification/context.st",
                "classpath:/prompts/tools/");
        return new PromptResourceCatalogLoader(new DefaultResourceLoader()).load(properties, registry);
    }

    private static PromptResourceCatalog stubPromptCatalog() {
        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        when(catalog.actionSystemInstruction()).thenReturn("resource system");
        when(catalog.renderActionContext(org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn("resource context");
        when(catalog.catalogDigest()).thenReturn("catalog-digest");
        return catalog;
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
                Map.of(), Map.of(), List.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private AgentPromptContext contextWithInteractions(List<ModelInteraction> interactions) {
        AgentPromptContext context = context();
        return new AgentPromptContext(context.originalQuestion(), context.sessionHistory(), context.runId(), context.attemptId(),
                context.issuedCapabilities(), context.issuedCandidates(), context.issuedEvidence(), context.observations(),
                interactions, context.latestRejection(), context.budget());
    }

    private CapabilityHandle capability() {
        return context().issuedCapabilities().keySet().iterator().next();
    }

    private static AgentActionFingerprint fingerprint(AgentAction action) {
        return AgentActionFingerprint.from(action);
    }

    private static AgentActionFingerprint payloadFingerprint(AgentAction action) {
        return AgentActionFingerprint.executionPayloadFrom(action);
    }

    private HandleBinding binding(String attemptId, String revision) {
        return new HandleBinding(new AnalysisRunId("run-1"), new AnalysisAttemptId(attemptId),
                RevisionVector.empty().pin(new RepositoryId("repo-1"), new RepositoryRevision(revision)));
    }

    private static AnswerDocument answerDocument(
            Set<EvidenceHandleRef> citations,
            Set<ObservationId> observationIds,
            String statementText) {
        return new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"), StatementType.FACT,
                statementText, Optional.of(new ClaimId("claim-1")), citations, observationIds)));
    }

    private static List<String> formattedMessages(CapturingHandler handler) {
        return handler.records().stream()
                .map(record -> new MessageFormat(record.getMessage()).format(record.getParameters()))
                .toList();
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
                List.of(), Optional.empty(), new AttemptBudget(3, 0, 3, 0, 1, 0, 3, 0, 1, 0));
    }

    private record ToolInput(
            @JsonProperty(required = true) List<String> candidateHandles,
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale) {
    }

    private record FingerprintExecutionInput(List<String> candidateHandles) {
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
        private Optional<Prompt> lastPrompt = Optional.empty();

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
            lastPrompt = Optional.of(prompt);
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return new ChatResponse(List.of(new Generation(assistantMessage.orElseGet(() -> new AssistantMessage(response)))));
        }

        private int calls() {
            return calls.get();
        }

        private Optional<Prompt> lastPrompt() {
            return lastPrompt;
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
