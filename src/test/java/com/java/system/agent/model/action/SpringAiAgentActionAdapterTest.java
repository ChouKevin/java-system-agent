package com.java.system.agent.model.action;

import com.google.genai.errors.ClientException;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
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
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentActionTransportException;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.ExternalExecutionDeferredException;
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
        CountingChatModel model = new CountingChatModel("""
                {"type":"QUERY","query":{"capabilityHandle":"cap-1","candidateHandles":["candidate-2","candidate-1"],"questionToResolve":"Which route calls it?","arguments":{"depth":"2"},"rationale":"Trace callers"},"answer":null,"clarify":null}
                """);
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        QueryAction action = (QueryAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.capability().value()).isEqualTo("cap-1");
        assertThat(action.candidates()).extracting(CandidateHandle::value).containsExactly("candidate-2", "candidate-1");
        assertThat(action.arguments()).containsExactly(Map.entry("depth", "2"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsImpossibleEnvelopeToSanitizedMalformedWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"QUERY","query":null,"answer":null,"clarify":null}
                """);
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void rejectsContradictoryMultiPayloadEnvelopeWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"QUERY","query":{"capabilityHandle":"cap-1","candidateHandles":["candidate-1"],"questionToResolve":"Which route calls it?","arguments":{"depth":"2"},"rationale":"Trace callers"},"answer":null,"clarify":{"question":"Which repository?","candidateHandles":["candidate-1"],"reason":"Ambiguous"}}
                """);
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isEqualTo(new AgentActionProposal.Malformed("MALFORMED_ACTION_RESPONSE"));
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsAnswerWithExactCitationAndObservationReferences() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"ANSWER","query":null,"answer":{"statements":[{"statementId":"statement-1","type":"FACT","text":"It is called by checkout","claimId":"claim-1","citationHandles":["evidence-1"],"observationIds":["observation-1"]}]},"clarify":null}
                """);
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        AgentActionProposal proposal = adapter.nextAction(answerContext());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        AnswerAction action = (AnswerAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.document().statements()).hasSize(1);
        assertThat(action.document().statements().getFirst().citations()).extracting(EvidenceHandle::value).containsExactly("evidence-1");
        assertThat(action.document().statements().getFirst().observationIds()).extracting(ObservationId::value).containsExactly("observation-1");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsClarifyWithModelCandidateOrder() {
        CountingChatModel model = new CountingChatModel("""
                {"type":"CLARIFY","query":null,"answer":null,"clarify":{"question":"Which repository?","candidateHandles":["candidate-2","candidate-1"],"reason":"The route is ambiguous"}}
                """);
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        AgentActionProposal proposal = adapter.nextAction(context());

        assertThat(proposal).isInstanceOf(AgentActionProposal.Proposed.class);
        ClarifyAction action = (ClarifyAction) ((AgentActionProposal.Proposed) proposal).action();
        assertThat(action.candidates()).extracting(CandidateHandle::value).containsExactly("candidate-2", "candidate-1");
        assertThat(action.question()).isEqualTo("Which repository?");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsConversionFailureToMalformedAndGeneralTransportToSanitizedUnavailable() {
        CountingChatModel malformedModel = new CountingChatModel("not json");
        SpringAiAgentActionAdapter malformedAdapter = new SpringAiAgentActionAdapter(ChatClient.builder(malformedModel).build());
        CountingChatModel unavailableModel = new CountingChatModel(new IllegalStateException("provider response omitted"));
        SpringAiAgentActionAdapter unavailableAdapter = new SpringAiAgentActionAdapter(ChatClient.builder(unavailableModel).build());

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
        assertThat(prompt.indexOf("Remaining budget")).isLessThan(prompt.indexOf("Final-response mode"));
        assertThat(prompt.indexOf("Final-response mode")).isLessThan(prompt.indexOf("Response contract"));
        assertThat(AgentActionPromptRenderer.SYSTEM_INSTRUCTION).contains("Use only issued opaque handles")
                .doesNotContain("memory", "advisor", "tool");
    }

    @Test
    void classifiesRateLimitTransportFailureWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new ResourceExhaustedException());
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

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
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.nextAction(context())).isSameAs(deferral);
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void classifiesWrappedGoogleGenAi429WithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new IllegalStateException(
                new ClientException(429, "RESOURCE_EXHAUSTED", "secret provider body")));
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.nextAction(context()))
                .isInstanceOf(AgentActionTransportException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void classifiesHttp429TransportMetadataWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));
        SpringAiAgentActionAdapter adapter = new SpringAiAgentActionAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.nextAction(context()))
                .isInstanceOf(AgentActionTransportException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(model.calls()).isEqualTo(1);
    }

    private AgentPromptContext context() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RevisionVector revisions = RevisionVector.empty().pin(new RepositoryId("repo-1"), new RepositoryRevision("rev-1"));
        HandleBinding binding = new HandleBinding(runId, attemptId, revisions);
        CapabilityHandle capability = new CapabilityHandle("cap-1", binding);
        CandidateHandle firstCandidate = new CandidateHandle("candidate-1", binding, CandidateKind.REPOSITORY);
        CandidateHandle secondCandidate = new CandidateHandle("candidate-2", binding, CandidateKind.REPOSITORY);
        CapabilityDescriptor descriptor = new CapabilityDescriptor("callers", "v1", Set.of(CandidateKind.REPOSITORY), 1, 2,
                new CapabilityQuerySchema(List.of()));
        return new AgentPromptContext("Where is it called?", SessionHistory.empty(), runId, attemptId,
                Map.of(capability, descriptor),
                Map.of(firstCandidate, new IssuedCandidate(firstCandidate, new RepositoryCandidate(new RepositoryId("repo-1"), "first")),
                        secondCandidate, new IssuedCandidate(secondCandidate, new RepositoryCandidate(new RepositoryId("repo-2"), "second"))),
                Map.of(), Map.of(), java.util.Optional.empty(), new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0, 1, 0), false);
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
                Optional.empty(), new AttemptBudget(3, 0, 3, 0, 3, 0, 1, 0, 1, 0), false);
    }

    private static final class CountingChatModel implements ChatModel {

        private final String response;
        private final Optional<RuntimeException> failure;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingChatModel(String response) {
            this.response = response;
            this.failure = Optional.empty();
        }

        private CountingChatModel(RuntimeException failure) {
            this.response = "";
            this.failure = Optional.of(failure);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
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
