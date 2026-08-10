package com.java.system.agent.model.verification;

import com.java.system.agent.model.verification.dto.AnswerVerdictResponse;
import com.java.system.agent.model.verification.dto.StatementVerdictResponse;
import com.java.system.agent.model.prompt.CapabilityReference;
import com.java.system.agent.model.prompt.ConfiguredEvidenceRequirement;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.ClaimId;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.answer.StatementVerdictStatus;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.evidence.ArtifactRef;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.ExecutionDeferral;
import com.java.system.agent.answering.domain.run.ExecutionDeferralReason;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring AI 回答 verifier strategy 與持久化 mode dispatch 邊界測試
 */
class AnswerVerificationAdapterTest {

    @Test
    void mapsLlmAcceptedCompleteWithoutVerdictsForNonFactDocument() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter adapter = adapter(model);

        AnswerVerificationResult result = adapter.verify(AnswerVerificationMode.LLM, context());

        assertThat(result).isInstanceOf(AnswerVerificationResult.LlmVerdict.class);
        AnswerVerificationResult.LlmVerdict verdict = (AnswerVerificationResult.LlmVerdict) result;
        assertThat(verdict.verdict().disposition()).isEqualTo(AnswerDisposition.ACCEPTED_COMPLETE);
        assertThat(verdict.verdict().statementVerdicts()).isEmpty();
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void sends_one_nonblank_resource_system_and_schema_supplied_user_message() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        when(catalog.verificationSystemInstruction()).thenReturn("verification system resource");
        when(catalog.renderVerificationContext(anyMap())).thenAnswer(invocation ->
                "verification user resource " + ((java.util.Map<?, ?>) invocation.getArgument(0))
                        .get("responseContract"));
        when(catalog.catalogDigest()).thenReturn("catalog-sha256");
        SpringAiAnswerVerificationAdapter adapter = new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build(),
                catalog, new AnswerVerificationPromptRenderer(catalog), interpreter(List.of()));
        Logger logger = Logger.getLogger(SpringAiAnswerVerificationAdapter.class.getName());
        CapturingLogHandler handler = new CapturingLogHandler();
        logger.addHandler(handler);

        try {
            adapter.verify(AnswerVerificationMode.LLM, context());
        } finally {
            logger.removeHandler(handler);
        }

        List<Message> messages = model.prompt().getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.getFirst()).getText()).isNotBlank();
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(1)).getText())
                .contains("verification user resource", "ACCEPTED_COMPLETE");
        assertThat(model.calls()).isEqualTo(1);
        assertThat(handler.formattedMessage()).contains("catalogSha256=catalog-sha256")
                .doesNotContain("verification system resource", "verification user resource");
    }

    @Test
    void correctsMissingConfiguredEvidenceUnlessAnInconclusiveAnswerReferencesAnObservation() {
        String response = """
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[{"statementId":"statement-1","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """;
        CountingChatModel missingModel = new CountingChatModel(response);
        SpringAiAnswerVerificationAdapter missingAdapter = configuredAdapter(missingModel);
        CountingChatModel coveredModel = new CountingChatModel(response);
        SpringAiAnswerVerificationAdapter coveredAdapter = configuredAdapter(coveredModel);
        CountingChatModel wrongVersionModel = new CountingChatModel(response);
        SpringAiAnswerVerificationAdapter wrongVersionAdapter = configuredAdapter(wrongVersionModel);
        String inconclusiveResponse = """
                {"disposition":"ACCEPTED_INCONCLUSIVE","statementVerdicts":[{"statementId":"statement-1","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":["Unavailable"],"blockingUncertainties":["Missing source proof"],"rejectionReasons":[]}
                """;
        CountingChatModel inconclusiveModel = new CountingChatModel(inconclusiveResponse);
        SpringAiAnswerVerificationAdapter inconclusiveAdapter = configuredAdapter(inconclusiveModel);
        CountingChatModel observedInconclusiveModel = new CountingChatModel(inconclusiveResponse);
        SpringAiAnswerVerificationAdapter observedInconclusiveAdapter = configuredAdapter(observedInconclusiveModel);
        CountingChatModel runtimeObservedInconclusiveModel = new CountingChatModel(inconclusiveResponse);
        SpringAiAnswerVerificationAdapter runtimeObservedInconclusiveAdapter =
                configuredAdapter(runtimeObservedInconclusiveModel);

        AnswerVerificationResult.LlmVerdict missingResult = (AnswerVerificationResult.LlmVerdict) missingAdapter.verify(
                AnswerVerificationMode.LLM, configuredEvidenceCoverageContext("test_method_source", "v7", false));
        AnswerVerificationResult.LlmVerdict coveredResult = (AnswerVerificationResult.LlmVerdict) coveredAdapter.verify(
                AnswerVerificationMode.LLM, configuredEvidenceCoverageContext("test_method_source", "v7", true));
        AnswerVerificationResult.LlmVerdict wrongVersionResult = (AnswerVerificationResult.LlmVerdict) wrongVersionAdapter.verify(
                AnswerVerificationMode.LLM, configuredEvidenceCoverageContext("test_method_source", "v6", true));
        AnswerVerificationResult.LlmVerdict inconclusiveResult = (AnswerVerificationResult.LlmVerdict) inconclusiveAdapter.verify(
                AnswerVerificationMode.LLM, configuredEvidenceCoverageContext("test_method_source", "v7", false));
        AnswerVerificationResult.LlmVerdict observedInconclusiveResult =
                (AnswerVerificationResult.LlmVerdict) observedInconclusiveAdapter.verify(
                        AnswerVerificationMode.LLM,
                        configuredEvidenceCoverageContext(
                                "test_method_source", "v7", false, ObservationSource.CAPABILITY_EXECUTOR));
        AnswerVerificationResult.LlmVerdict runtimeObservedInconclusiveResult =
                (AnswerVerificationResult.LlmVerdict) runtimeObservedInconclusiveAdapter.verify(
                        AnswerVerificationMode.LLM,
                        configuredEvidenceCoverageContext(
                                "test_method_source", "v7", false, ObservationSource.RUNTIME));

        assertThat(missingResult.verdict().disposition()).isEqualTo(AnswerDisposition.REJECTED);
        assertThat(missingResult.verdict().unaddressedParts())
                .containsExactly("configured evidence requirement source-proof requires cited evidence");
        assertThat(missingResult.verdict().rejectionReasons())
                .containsExactly("explicitly requested evidence requirement is not cited: source-proof");
        assertThat(coveredResult.verdict().disposition()).isEqualTo(AnswerDisposition.ACCEPTED_COMPLETE);
        assertThat(wrongVersionResult.verdict().disposition()).isEqualTo(AnswerDisposition.REJECTED);
        assertThat(inconclusiveResult.verdict().disposition()).isEqualTo(AnswerDisposition.REJECTED);
        assertThat(observedInconclusiveResult.verdict().disposition())
                .isEqualTo(AnswerDisposition.ACCEPTED_INCONCLUSIVE);
        assertThat(runtimeObservedInconclusiveResult.verdict().disposition()).isEqualTo(AnswerDisposition.REJECTED);
        assertThat(missingModel.calls()).isEqualTo(1);
        assertThat(coveredModel.calls()).isEqualTo(1);
        assertThat(wrongVersionModel.calls()).isEqualTo(1);
        assertThat(inconclusiveModel.calls()).isEqualTo(1);
        assertThat(observedInconclusiveModel.calls()).isEqualTo(1);
        assertThat(runtimeObservedInconclusiveModel.calls()).isEqualTo(1);
    }

    @Test
    void doesNotTreatAnOverlappingShorterAliasAsAnAdditionalEvidenceRequest() {
        String response = """
                {"disposition":"ACCEPTED_COMPLETE","statementVerdicts":[{"statementId":"statement-1","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """;
        CountingChatModel model = new CountingChatModel(response);
        List<ConfiguredEvidenceRequirement> requirements = List.of(
                new ConfiguredEvidenceRequirement("source-proof",
                        new CapabilityReference("test_method_source", "v7"), List.of("source proof")),
                new ConfiguredEvidenceRequirement("generic-proof",
                        new CapabilityReference("test_generic_proof", "v1"), List.of("proof")));
        SpringAiAnswerVerificationAdapter adapter = adapter(model, requirements);

        AnswerVerificationResult.LlmVerdict result = (AnswerVerificationResult.LlmVerdict) adapter.verify(
                AnswerVerificationMode.LLM, configuredEvidenceCoverageContext("test_method_source", "v7", true));

        assertThat(result.verdict().disposition()).isEqualTo(AnswerDisposition.ACCEPTED_COMPLETE);
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void mapsInconclusiveAndRejectedDispositionsWithoutChangingTheirDetails() {
        CountingChatModel inconclusiveModel = new CountingChatModel("""
                {"disposition":"ACCEPTED_INCONCLUSIVE","statementVerdicts":[],"unaddressedParts":["Missing detail"],"blockingUncertainties":["Unknown implementation"],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter inconclusive = adapter(inconclusiveModel);
        CountingChatModel rejectedModel = new CountingChatModel("""
                {"disposition":"REJECTED","statementVerdicts":[],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":["Citation is absent"]}
                """);
        SpringAiAnswerVerificationAdapter rejected = adapter(rejectedModel);

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
        SpringAiAnswerVerificationAdapter adapter = adapter(model);

        AnswerVerificationResult.LlmVerdict result = (AnswerVerificationResult.LlmVerdict) adapter.verify(
                AnswerVerificationMode.LLM, limitationContext());

        assertThat(result.verdict().statementVerdicts()).isEmpty();
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void treatsMalformedVerifierOutputAsUnavailableNotRejected() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"REJECTED","statementVerdicts":[{"statementId":"statement-1","status":"UNDECIDED","description":"Unknown"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":[]}
                """);
        SpringAiAnswerVerificationAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context()))
                .isInstanceOf(AnswerVerificationUnavailableException.class)
                .hasMessage("ANSWER_VERIFIER_UNAVAILABLE");
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void treatsGenericVerifierTransportFailureAsSanitizedUnavailable() {
        CountingChatModel model = new CountingChatModel(new IllegalStateException("provider response omitted"));
        SpringAiAnswerVerificationAdapter adapter = adapter(model);

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
        SpringAiAnswerVerificationAdapter llm = adapter(model);
        AnswerVerificationDispatcher dispatcher = new AnswerVerificationDispatcher(llm, new ContractOnlyAnswerVerificationAdapter());

        AnswerVerificationResult result = dispatcher.verify(AnswerVerificationMode.CONTRACT_ONLY, context());

        assertThat(result).isEqualTo(new AnswerVerificationResult.ContractAccepted());
        assertThat(model.calls()).isZero();
    }

    @Test
    void classifiesVerifierResourceExhaustionWithoutAnotherCall() {
        CountingChatModel model = new CountingChatModel(new ResourceExhaustedException());
        SpringAiAnswerVerificationAdapter adapter = adapter(model);

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
        SpringAiAnswerVerificationAdapter adapter = adapter(model);

        assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, context())).isSameAs(deferral);
        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void constrainsVerifierResponseContractToTheSupportedDomainValues() {
        String responseContract = new BeanOutputConverter<>(AnswerVerdictResponse.class).getFormat();

        assertThat(responseContract).contains("ACCEPTED_COMPLETE", "ACCEPTED_INCONCLUSIVE", "REJECTED",
                "SUPPORTED", "UNSUPPORTED");
    }

    @Test
    void logsOnlySafeCountsWhenVerifierOmitsAFactVerdict() {
        CountingChatModel model = new CountingChatModel("""
                {"disposition":"REJECTED","statementVerdicts":[{"statementId":"statement-a","status":"SUPPORTED","description":"Supported"}],"unaddressedParts":[],"blockingUncertainties":[],"rejectionReasons":["Incomplete"]}
                """);
        SpringAiAnswerVerificationAdapter adapter = adapter(model);
        Logger logger = Logger.getLogger(AnswerVerdictResponseInterpreter.class.getName());
        CapturingLogHandler handler = new CapturingLogHandler();
        logger.addHandler(handler);

        try {
            assertThatThrownBy(() -> adapter.verify(AnswerVerificationMode.LLM, richContext()))
                    .isInstanceOf(AnswerVerificationUnavailableException.class);
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(handler.formattedMessage())
                .contains("expectedFactStatementCount=2", "returnedStatementVerdictCount=1",
                        "missingFactStatementVerdictCount=1", "unexpectedStatementVerdictCount=0")
                .doesNotContain("statement-a", "statement-b", "First fact", "Second fact");
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
    void rejectsDuplicateMissingAndNullStatementVerdictShapes() {
        AnswerVerdictResponseInterpreter interpreter = interpreter(List.of());
        AnswerVerdictResponse duplicate = new AnswerVerdictResponse(AnswerDisposition.ACCEPTED_COMPLETE, List.of(
                new StatementVerdictResponse("statement-a", StatementVerdictStatus.SUPPORTED, "Supported"),
                new StatementVerdictResponse("statement-a", StatementVerdictStatus.SUPPORTED, "Supported twice")),
                List.of(), List.of(), List.of());
        AnswerVerdictResponse missing = new AnswerVerdictResponse(AnswerDisposition.ACCEPTED_COMPLETE,
                List.of(), List.of(), List.of(), List.of());
        AnswerVerdictResponse missingStatus = new AnswerVerdictResponse(AnswerDisposition.ACCEPTED_COMPLETE, List.of(
                new StatementVerdictResponse("statement-a", null, "Unknown")), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> interpreter.interpret(duplicate, richContext())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interpreter.interpret(missing, richContext())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interpreter.interpret(missingStatus, richContext())).isInstanceOf(NullPointerException.class);
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
                        Optional.of(new com.java.system.agent.answering.domain.answer.ClaimId("claim-b")),
                        Set.of(new EvidenceHandleRef(evidenceB.value()), new EvidenceHandleRef(evidenceA.value())), Set.of(observationB, observationA)),
                new AnswerStatement(new StatementId("statement-a"), StatementType.FACT, "First fact",
                        Optional.of(new com.java.system.agent.answering.domain.answer.ClaimId("claim-a")),
                        Set.of(new EvidenceHandleRef(evidenceA.value())), Set.of(observationA))));
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

    private AnswerVerificationContext configuredEvidenceCoverageContext(
            String capabilityName,
            String capabilityVersion,
            boolean hasCitedProvenance) {
        return configuredEvidenceCoverageContext(
                capabilityName, capabilityVersion, hasCitedProvenance, Optional.empty());
    }

    private AnswerVerificationContext configuredEvidenceCoverageContext(
            String capabilityName,
            String capabilityVersion,
            boolean hasCitedProvenance,
            ObservationSource observationSource) {
        return configuredEvidenceCoverageContext(
                capabilityName, capabilityVersion, hasCitedProvenance, Optional.of(observationSource));
    }

    private AnswerVerificationContext configuredEvidenceCoverageContext(
            String capabilityName,
            String capabilityVersion,
            boolean hasCitedProvenance,
            Optional<ObservationSource> observationSource) {
        AnalysisRunId runId = new AnalysisRunId("run-source-proof");
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-source-proof");
        RepositoryId repositoryId = new RepositoryId("repo-source-proof");
        RepositoryRevision revision = new RepositoryRevision("rev-source-proof");
        HandleBinding binding = new HandleBinding(runId, attemptId,
                RevisionVector.empty().pin(repositoryId, revision));
        EvidenceHandle evidenceHandle = new EvidenceHandle("evidence-source-proof", binding);
        IssuedEvidence issuedEvidence = new IssuedEvidence(evidenceHandle,
                evidence(repositoryId, revision, "source proof", "digest-source-proof"));
        CapabilityPolicy capability = new CapabilityPolicy(capabilityName, capabilityVersion,
                Set.of(CandidateKind.SEMANTIC_TARGET), 1, 1);
        ObservationId observationId = new ObservationId("observation-source-proof");
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.FACT, "Source proof result",
                Optional.of(new ClaimId("claim-source-proof")), Set.of(new EvidenceHandleRef(evidenceHandle.value())),
                observationSource.isPresent() ? Set.of(observationId) : Set.of())));
        List<EvidenceCapabilityProvenance> provenance = hasCitedProvenance
                ? List.of(new EvidenceCapabilityProvenance(evidenceHandle, capability)) : List.of();
        List<AgentObservation> observations = observationSource
                .map(source -> List.of(new AgentObservation(
                        observationId, source, ObservationCode.PARTIAL_GRAPH, "Source proof is incomplete",
                        Set.of(), Set.of(evidenceHandle), "test-capability")))
                .orElseGet(List::of);
        return new AnswerVerificationContext("Request source proof", SessionHistory.empty(), document,
                List.of(issuedEvidence), observations, List.of(issuedEvidence), observations, provenance);
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

    private static List<ConfiguredEvidenceRequirement> evidenceRequirements() {
        return List.of(new ConfiguredEvidenceRequirement(
                "source-proof", new CapabilityReference("test_method_source", "v7"), List.of("source proof")));
    }

    private static PromptResourceCatalog promptCatalog() {
        PromptResourceCatalog catalog = mock(PromptResourceCatalog.class);
        when(catalog.verificationSystemInstruction()).thenReturn("verification system resource");
        when(catalog.renderVerificationContext(anyMap())).thenReturn("verification context resource");
        when(catalog.catalogDigest()).thenReturn("catalog-sha256");
        return catalog;
    }

    private static SpringAiAnswerVerificationAdapter adapter(CountingChatModel model) {
        return adapter(model, List.of());
    }

    private static SpringAiAnswerVerificationAdapter configuredAdapter(CountingChatModel model) {
        return adapter(model, evidenceRequirements());
    }

    private static SpringAiAnswerVerificationAdapter adapter(
            CountingChatModel model,
            List<ConfiguredEvidenceRequirement> requirements) {
        PromptResourceCatalog catalog = promptCatalog();
        return new SpringAiAnswerVerificationAdapter(ChatClient.builder(model).build(), catalog,
                new AnswerVerificationPromptRenderer(catalog), interpreter(requirements));
    }

    private static AnswerVerdictResponseInterpreter interpreter(List<ConfiguredEvidenceRequirement> requirements) {
        return new AnswerVerdictResponseInterpreter(new ExplicitEvidenceCoveragePolicy(requirements));
    }

    private static final class CountingChatModel implements ChatModel {

        private final String response;
        private final Optional<RuntimeException> failure;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();

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
            this.prompt.set(prompt);
            if (failure.isPresent()) {
                throw failure.orElseThrow();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }

        private int calls() {
            return calls.get();
        }

        private Prompt prompt() {
            return prompt.get();
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

    private static final class CapturingLogHandler extends Handler {

        private String formattedMessage = "";

        @Override
        public void publish(LogRecord record) {
            formattedMessage = java.text.MessageFormat.format(record.getMessage(), record.getParameters());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String formattedMessage() {
            return formattedMessage;
        }
    }
}
