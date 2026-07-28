package com.java.system.agent.model.verification;

import com.java.system.agent.model.verification.dto.AnswerVerdictResponse;
import com.java.system.agent.model.verification.dto.StatementVerdictResponse;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.ExecutionDeferral;
import com.java.system.agent.runtime.domain.run.ExecutionDeferralReason;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
import com.java.system.agent.runtime.port.out.AnswerVerificationResult;
import com.java.system.agent.runtime.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.runtime.port.out.ExternalExecutionDeferredException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring AI 回答 verifier strategy 與持久化 mode dispatch 邊界測試
 */
class AnswerVerificationAdapterTest {

    @Test
    void mapsLlmAcceptedCompleteWithoutVerdictsForNonFactDocument() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        AnswerVerificationResult result = adapter.verify(AnswerVerificationMode.LLM, context());

        assertThat(result).isInstanceOf(AnswerVerificationResult.LlmVerdict.class);
        AnswerVerificationResult.LlmVerdict verdict = (AnswerVerificationResult.LlmVerdict) result;
        assertThat(verdict.verdict().disposition()).isEqualTo(AnswerDisposition.ACCEPTED_COMPLETE);
        assertThat(verdict.verdict().statementVerdicts()).isEmpty();
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsInconclusiveAndRejectedDispositionsWithoutChangingTheirDetails() {
        CountingChatModel inconclusiveModel = new CountingChatModel("""
                {"disposition":"ACCEPTED_INCONCLUSIVE","statementVerdicts":[],"unaddressedParts":["Missing detail"],"blockingUncertainties":["Unknown implementation"],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter inconclusive = new SpringAiAnswerVerificationAdapter(ChatClient.builder(inconclusiveModel).build());
        CountingChatModel rejectedModel = new CountingChatModel("""
                {"disposition":"REJECTED","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":["Citation is absent"]}
                """);
        SpringAiAnswerVerificationAdapter rejected = new SpringAiAnswerVerificationAdapter(ChatClient.builder(rejectedModel).build());

        AnswerVerificationResult.LlmVerdict inconclusiveResult = (AnswerVerificationResult.LlmVerdict) inconclusive.verify(AnswerVerificationMode.LLM, context());
        AnswerVerificationResult.LlmVerdict rejectedResult = (AnswerVerificationResult.LlmVerdict) rejected.verify(AnswerVerificationMode.LLM, context());

        assertThat(inconclusiveResult.verdict().disposition()).isEqualTo(AnswerDisposition.ACCEPTED_INCONCLUSIVE);
        assertThat(inconclusiveResult.verdict().unaddressedParts()).containsExactly("Missing detail");
        assertThat(rejectedResult.verdict().disposition()).isEqualTo(AnswerDisposition.REJECTED);
        assertThat(rejectedResult.verdict().rejectionReasons()).containsExactly("Citation is absent");
        assertThat(inconclusiveModel.calls()).isEqualTo(1);
        assertThat(rejectedModel.calls()).isEqualTo(1);
    }

    @Test
    void acceptsAnEmptyVerdictSetForALimitationOnlyDocument() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        AnswerVerificationResult.LlmVerdict result = (AnswerVerificationResult.LlmVerdict) adapter.verify(
                AnswerVerificationMode.LLM, limitationContext());

        assertThat(result.verdict().statementVerdicts()).isEmpty();
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void treatsMalformedVerifierOutputAsUnavailableNotRejected() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"REJECTED","statementVerdicts":[{"statementId":"statement-1","status":"UNSUPPORTED","description":"Unsupported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context()))
                .isInstanceOf(AnswerVerificationUnavailableException.class)
                .hasMessage("ANSWER_VERIFIER_UNAVAILABLE");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void treatsGenericVerifierTransportFailureAsSanitizedUnavailable() {
        CountingChatModel model = new CountingChatModel(new IllegalStateException("provider response omitted"));
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context()))
                .isInstanceOf(AnswerVerificationUnavailableException.class)
                .hasMessage("ANSWER_VERIFIER_UNAVAILABLE");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void contractOnlyReturnsContractAcceptedWithoutAnyModelCall() {
        ContractOnlyAnswerVerificationAdapter adapter = new ContractOnlyAnswerVerificationAdapter();

        AnswerVerificationResult result = adapter.verify(AnswerVerificationMode.CONTRACT_ONLY, context());

        assertThat(result).isEqualTo(new AnswerVerificationResult.ContractAccepted());
    }

    @Test
    void dispatcherUsesPersistedModeRatherThanAnyCurrentDefault() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[{"statementId":"statement-1","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter llm = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());
        AnswerVerificationDispatcher dispatcher = new AnswerVerificationDispatcher(llm, new ContractOnlyAnswerVerificationAdapter());

        AnswerVerificationResult result = dispatcher.verify(AnswerVerificationMode.CONTRACT_ONLY, context());

        assertThat(result).isEqualTo(new AnswerVerificationResult.ContractAccepted());
        assertThat(model.calls()).isZero();
    }

    @Test
    void classifiesVerifierResourceExhaustionWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new ResourceExhaustedException());
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context()))
                .isInstanceOf(AnswerVerificationUnavailableException.class)
                .hasMessage("RATE_LIMITED");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void preservesAnExternalExecutionDeferralWithoutVerifierClassification() {
        ExternalExecutionDeferredException deferral = new ExternalExecutionDeferredException(
                new ExecutionDeferral(Instant.parse("2026-07-28T01:02:03Z"), ExecutionDeferralReason.RATE_LIMITED));
        CountingChatModel model = new CountingChatModel(deferral);
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build());

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context())).isSameAs(deferral);
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void rendersOnlyVerifierContextWithoutActionControlTerms() {
        String prompt = new AnswerVerificationPromptRenderer().render(context(), "response-schema");

        assertThat(prompt).containsSubsequence("Current question", "Session history", "Proposed document", "Available evidence",
                "Available observations", "Cited evidence", "Referenced observations", "Response contract");
        assertThat(prompt.toLowerCase(java.util.Locale.ROOT)).doesNotContain("capability", "candidate", "budget", "action");
        assertThat(AnswerVerificationPromptRenderer.SYSTEM_INSTRUCTION.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("capability", "candidate", "budget", "action");
    }

    @Test
    void rendersStatementReferencesAndGlobalVerifierContextInDeterministicOrder() {
        String prompt = new AnswerVerificationPromptRenderer().render(richContext(), "response-schema");

        assertThat(prompt).contains("participant[test:participant-1]: Earlier question", "assistant: Earlier answer");
        assertThat(prompt).contains("statement-b [FACT]: Second fact", "claimId: claim-b",
                "citationHandles: evidence-a, evidence-b", "observationIds: observation-a, observation-b");
        assertThat(prompt).contains("statement-a [FACT]: First fact", "claimId: claim-a",
                "citationHandles: evidence-a", "observationIds: observation-a");
        assertThat(prompt.indexOf("- evidence-a: Evidence A")).isLessThan(prompt.indexOf("- evidence-b: Evidence B"));
        assertThat(prompt.indexOf("- observation-a: Observation A"))
                .isLessThan(prompt.indexOf("- observation-b: Observation B"));
    }

    @Test
    void dispatcherRejectsDuplicateMissingExtraAndNullStrategies() {
        AnswerVerificationStrategy llm = new FixedStrategy(AnswerVerificationMode.LLM);
        AnswerVerificationStrategy contractOnly = new FixedStrategy(AnswerVerificationMode.CONTRACT_ONLY);

        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm, llm)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm, contractOnly, contractOnly))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerVerificationDispatcher(llm, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsDuplicateMissingAndUnknownStatementVerdictShapes() {
        AnswerVerdictResponseInterpreter interpreter = new AnswerVerdictResponseInterpreter();
        AnswerVerdictResponse duplicate = new AnswerVerdictResponse("ACCEPTED_COMPLETE", List.of(
                new StatementVerdictResponse("statement-a", "SUPPORTED", "Supported"),
                new StatementVerdictResponse("statement-a", "SUPPORTED", "Supported twice")), List.of(), List.of(), List.of());
        AnswerVerdictResponse missing = new AnswerVerdictResponse("ACCEPTED_COMPLETE", List.of(), List.of(), List.of(), List.of());
        AnswerVerdictResponse unknownStatus = new AnswerVerdictResponse("ACCEPTED_COMPLETE", List.of(
                new StatementVerdictResponse("statement-a", "UNDECIDED", "Unknown")), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> interpreter.interpret(duplicate, richContext())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interpreter.interpret(missing, richContext())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interpreter.interpret(unknownStatus, richContext())).isInstanceOf(IllegalArgumentException.class);
    }

    private AnswerVerificationContext context() {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"),
                StatementType.UNCERTAINTY, "The implementation may differ", Optional.empty(), Set.of(), Set.of())));
        return new AnswerVerificationContext("What is known?", SessionHistory.empty(), document,
                List.of(), List.of(), List.of(), List.of());
    }

    private AnswerVerificationContext limitationContext() {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(new StatementId("limitation-1"),
                StatementType.LIMITATION, "The target remains unresolved", Optional.empty(), Set.of(), Set.of())));
        return new AnswerVerificationContext("What is known?", SessionHistory.empty(), document,
                List.of(), List.of(), List.of(), List.of());
    }

    private AnswerVerificationContext richContext() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        RepositoryId repositoryId = new RepositoryId("repo-1");
        RepositoryRevision revision = new RepositoryRevision("rev-1");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(runId, attemptId, revisions);
        EvidenceHandle evidenceB = new EvidenceHandle("evidence-b", binding);
        EvidenceHandle evidenceA = new EvidenceHandle("evidence-a", binding);
        ObservationId observationB = new ObservationId("observation-b");
        ObservationId observationA = new ObservationId("observation-a");
        AnswerDocument document = new AnswerDocument(List.of(
                new AnswerStatement(new StatementId("statement-b"), StatementType.FACT, "Second fact",
                        Optional.of(new com.java.system.agent.runtime.domain.answer.ClaimId("claim-b")),
                        Set.of(evidenceB, evidenceA), Set.of(observationB, observationA)),
                new AnswerStatement(new StatementId("statement-a"), StatementType.FACT, "First fact",
                        Optional.of(new com.java.system.agent.runtime.domain.answer.ClaimId("claim-a")),
                        Set.of(evidenceA), Set.of(observationA))));
        IssuedEvidence issuedEvidenceB = new IssuedEvidence(evidenceB, evidence(repositoryId, revision, "Evidence B", "digest-b"));
        IssuedEvidence issuedEvidenceA = new IssuedEvidence(evidenceA, evidence(repositoryId, revision, "Evidence A", "digest-a"));
        AgentObservation observationValueB = observation(observationB, evidenceB, "Observation B");
        AgentObservation observationValueA = observation(observationA, evidenceA, "Observation A");
        SessionHistory history = new SessionHistory(List.of(new ConversationTurn(runId, new ParticipantRef("test", "participant-1"), "Earlier question", "Earlier answer",
                ConversationTurnType.ANSWER)));
        return new AnswerVerificationContext("What is known?", history, document,
                List.of(issuedEvidenceB, issuedEvidenceA), List.of(observationValueB, observationValueA),
                List.of(issuedEvidenceB, issuedEvidenceA), List.of(observationValueB, observationValueA));
    }

    private EvidenceRef evidence(RepositoryId repositoryId, RepositoryRevision revision, String content, String digest) {
        return new EvidenceRef("semantic", repositoryId, revision,
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Checkout#call", Optional.empty()), content, List.of(),
                new ArtifactRef(digest));
    }

    private AgentObservation observation(ObservationId observationId, EvidenceHandle evidenceHandle, String description) {
        return new AgentObservation(observationId, ObservationSource.RUNTIME, ObservationCode.PARTIAL_GRAPH,
                description, Set.of(), Set.of(evidenceHandle), "runtime");
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

    private record FixedStrategy(AnswerVerificationMode mode) implements AnswerVerificationStrategy {

        @Override
        public AnswerVerificationResult verify(AnswerVerificationMode mode, AnswerVerificationContext context) {
            return new AnswerVerificationResult.ContractAccepted();
        }
    }
}
