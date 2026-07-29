package com.java.system.agent.model.action;

import com.google.genai.errors.ClientException;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolSchemaFactory;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.spi.CapabilityExecutionContext;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.ExecutionDeferral;
import com.java.system.agent.runtime.domain.run.ExecutionDeferralReason;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandleRef;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentActionTransportException;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.ExternalExecutionDeferredException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.domain.capability.CapabilityInputPayload;
import com.java.system.agent.runtime.domain.handle.CandidateHandleRef;
import jakarta.validation.Validation;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(action.candidates()).extracting(CandidateHandleRef::value).containsExactly("candidate-2", "candidate-1");
        assertThat(action.payload().value()).isEqualTo("{}");
        assertThat(model.calls()).isEqualTo(1);
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
    void mapsImpossibleEnvelopeToSanitizedMalformedWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"QUERY","query":null,"answer":null,"clarify":null}
                """);
        SpringAiAgentActionAdapter adapter = adapter(model);

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void rejectsContradictoryMultiPayloadEnvelopeWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"QUERY","query":{"capabilityHandle":"cap-1","candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","arguments":{"depth":"2"},"rationale":"Trace callers"},"answer":null,"clarify":{"question":"Which repository?","candidateHandles":["candidate-1"],"reason":"Ambiguous"}}
                """);
        SpringAiAgentActionAdapter adapter = adapter(model);

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsAnswerWithExactCitationAndObservationReferences() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"ANSWER","query":null,"answer":{"statements":[{"statementId":"statement-1","type":"FACT","text":"It is called by checkout","claimId":"claim-1","citationHandles":["evidence-1"],"observationIds":["observation-1"]}]},"clarify":null}
                """);
        SpringAiAgentActionAdapter adapter = adapter(model);

        AgentActionProposal proposal = adapter.nextAction(answerContext());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        AnswerAction action = (AnswerAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.document().statements()).hasSize(1);
        assertThat(action.document().statements().getFirst().citations()).extracting(EvidenceHandleRef::value).containsExactly("evidence-1");
        assertThat(action.document().statements().getFirst().observationIds()).extracting(ObservationId::value).containsExactly("observation-1");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsClarifyWithModelCandidateOrder() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"CLARIFY","query":null,"answer":null,"clarify":{"question":"Which repository?","candidateHandles":["candidate-2","candidate-1"],"reason":"The route is ambiguous"}}
                """);
        SpringAiAgentActionAdapter adapter = adapter(model);

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        ClarifyAction action = (ClarifyAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.candidates()).extracting(CandidateHandleRef::value).containsExactly("candidate-2", "candidate-1");
        assertThat(action.question()).isEqualTo("Which repository?");
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
        String prompt = new AgentActionPromptRenderer().render(context(), "response-schema");

        assertThat(prompt.indexOf("Original question")).isLessThan(prompt.indexOf("Session turns"));
        assertThat(prompt.indexOf("Session turns")).isLessThan(prompt.indexOf("Capabilities"));
        assertThat(prompt.indexOf("Capabilities")).isLessThan(prompt.indexOf("Candidates"));
        assertThat(prompt.indexOf("Candidates")).isLessThan(prompt.indexOf("Evidence"));
        assertThat(prompt.indexOf("Evidence")).isLessThan(prompt.indexOf("Observations"));
        assertThat(prompt.indexOf("Observations")).isLessThan(prompt.indexOf("Latest rejection"));
        assertThat(prompt.indexOf("Latest rejection")).isLessThan(prompt.indexOf("Remaining budget"));
        assertThat(prompt.indexOf("Remaining budget")).isLessThan(prompt.indexOf("Response contract"));
        assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION).contains("Use only issued opaque handles", "registered tool call")
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
        CapabilityPolicy policy = new CapabilityPolicy("callers", "v1", Set.of(CandidateKind.REPOSITORY), 1, 2);
        CapabilityExecutor<ToolInput> executor = (context, input) ->
                new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        ObjectMapper mapper = new ObjectMapper();
        PlanningToolRegistry registry = new PlanningToolRegistry(List.of(PlanningToolRegistry.registration(
                policy, ToolInput.class, ToolInput.class, new ToolInputMapper(), executor,
                new PlanningToolSchemaFactory(mapper))), new StrictPlanningToolDecoder(
                mapper, Validation.buildDefaultValidatorFactory().getValidator()), new CanonicalCapabilityPayloadCodec(mapper));
        return new SpringAiAgentActionAdapter(ChatClient.builder(model).build(), registry);
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
                Map.of(), Map.of(), java.util.Optional.empty(), new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0));
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
                Optional.empty(), new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0));
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
}
