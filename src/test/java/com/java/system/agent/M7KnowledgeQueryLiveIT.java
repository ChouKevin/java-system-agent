package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.run.PendingTerminalResponse;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.interaction.application.ClaimAdmissionCoordinator;
import com.java.system.agent.interaction.application.DeliveryProcessor;
import com.java.system.agent.interaction.application.DeliveryRetryPolicy;
import com.java.system.agent.interaction.application.DeliveryWorkApplicationService;
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceAcceptance;
import com.java.system.agent.interaction.domain.SourceAcceptanceStatus;
import com.java.system.agent.interaction.domain.SourceAdmission;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.interaction.domain.TransportEventId;
import com.java.system.agent.interaction.domain.delivery.DeliveryKind;
import com.java.system.agent.interaction.domain.delivery.DeliveryMessage;
import com.java.system.agent.interaction.domain.delivery.DeliveryProcessingOutcome;
import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.domain.delivery.DeliveryTransportResult;
import com.java.system.agent.interaction.port.in.AcceptSourceEventUseCase;
import com.java.system.agent.interaction.port.in.ProcessNextDeliveryUseCase;
import com.java.system.agent.interaction.port.in.ProcessNextInboxUseCase;
import com.java.system.agent.interaction.port.out.DeliveryOutboxPort;
import com.java.system.agent.interaction.port.out.DeliveryTransportPort;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以真實 runtime 與外部 live infrastructure 驗證 M7 repository 知識查詢
 */
@SpringBootTest(properties = "spring.profiles.include=m7-knowledge-live")
@ActiveProfiles("agent-runtime")
@Import(M7KnowledgeQueryLiveIT.DeliveryTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "M7_KNOWLEDGE_LIVE", matches = "true")
class M7KnowledgeQueryLiveIT {

    private static final String SOURCE_TYPE = "m7-knowledge-live";
    private static final String LEGACY_SOURCE_TYPE = "live-test";
    private static final long M7_LIVE_ADVISORY_LOCK_KEY = 7_431_172_007L;
    private static final Duration POLL_DELAY = Duration.ofSeconds(2);
    private static final Duration TERMINAL_TIMEOUT = Duration.ofMinutes(8);
    private static final KnowledgeScenario COMPREHENSIVE_SCENARIO = new KnowledgeScenario(
            "comprehensive",
            "在 m7-knowledge-query repository 中，訂單處理有哪些 HTTP API、排程與訊息消費入口？請列出各入口的 package、class、method，分別用 outgoing call-graph evidence 說明它們如何進入共同的 OrderWorkflow.processOrder 流程，再用 implementation 與 internal-reference evidence 驗證共同處理實作，並提供該實作方法的完整來源。",
            EvidenceExpectation.COMPREHENSIVE);
    private static final List<KnowledgeScenario> SEEDED_SCENARIOS = List.of(
            new KnowledgeScenario("http-workflow",
                    "Trace the HTTP order submission entry point to the shared workflow and cite revision-pinned evidence.",
                    EvidenceExpectation.HTTP_TO_WORKFLOW),
            new KnowledgeScenario("scheduled-workflow",
                    "Trace the scheduled pending-order recovery entry point to the shared workflow and cite revision-pinned evidence.",
                    EvidenceExpectation.SCHEDULE_TO_WORKFLOW),
            new KnowledgeScenario("rabbit-workflow",
                    "Trace the RabbitMQ order-request listener to the shared workflow and cite revision-pinned evidence.",
                    EvidenceExpectation.MESSAGE_TO_WORKFLOW),
            new KnowledgeScenario("workflow-implementation-source",
                    "Find the shared order workflow declaration, its implementation, and complete method source with revision-pinned citations.",
                    EvidenceExpectation.IMPLEMENTATION_AND_SOURCE));
    private static final KnowledgeScenario MISSING_SYMBOL_SCENARIO = new KnowledgeScenario(
            "missing-symbol",
            "Investigate whether InvoiceController.refundInvoice exists in the repository. Provide complete source and revision-pinned citations if evidence supports it.",
            EvidenceExpectation.MISSING_SYMBOL);
    private static final RepositoryId REPOSITORY_ID = new RepositoryId("m7-knowledge-query");
    private static final RepositoryRevision REPOSITORY_REVISION = new RepositoryRevision("FIXTURE");
    private static final RevisionVector EXPECTED_REVISIONS = RevisionVector.fromEntries(
            List.of(new RevisionVector.Entry(REPOSITORY_ID, REPOSITORY_REVISION)));
    private static final Pattern GRAPH_NODE_PATTERN = Pattern.compile(
            "(?:^|;\\s*)node=([^:;]+):[^;]*?:target=([^:;]+#[^:;]+\\.[^:;]+):external=");
    private static final Pattern GRAPH_EDGE_PATTERN = Pattern.compile("(?:^|;\\s*)edge=([^>;]+)>([^:;]+):");
    private static final Pattern SOURCE_SHA = Pattern.compile("[0-9a-f]{40}");

    @Autowired
    private AcceptSourceEventUseCase sourceAcceptance;

    @Autowired
    private ProcessNextInboxUseCase inboxProcessor;

    @Autowired
    private ProcessNextDeliveryUseCase deliveryProcessor;

    @Autowired
    private AgentTransitionPort transitionPort;

    @Autowired
    private RecordingDeliveryTransport recordingDeliveryTransport;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PromptResourceCatalog promptResourceCatalog;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void should_accept_complete_and_inconclusive_m7_knowledge_queries_through_the_normal_runtime()
            throws IOException, InterruptedException, SQLException {
        long seed = Long.parseLong(requiredEnvironment("M7_KNOWLEDGE_SEED"));
        String agentSourceSha = requiredSourceSha("M7_AGENT_SOURCE_SHA");
        String semanticSourceSha = requiredSourceSha("M7_SEMANTIC_SOURCE_SHA");
        KnowledgeScenario seededScenario = SEEDED_SCENARIOS.get(new Random(seed).nextInt(SEEDED_SCENARIOS.size()));
        String testRunIdentity = "m7-" + Instant.now().toEpochMilli() + "-"
                + System.getProperty("surefire.forkNumber", "default");
        try (Connection advisoryLockConnection = dataSource.getConnection()) {
            acquireM7LiveTestLock(advisoryLockConnection);
            try {
                clearTestOwnedRows();
                assertNoEligibleInboxBeforeSubmission();

                recordingDeliveryTransport.clear();
                ScenarioRun comprehensiveRun = acceptAndProcess(COMPREHENSIVE_SCENARIO, testRunIdentity);
                PendingTerminalResponse.Answer comprehensiveAnswer = assertCompletedAcceptedState(
                        comprehensiveRun.state(), comprehensiveRun.admission());
                CitedEvidence comprehensiveEvidence = assertCitationsAndEvidence(
                        comprehensiveRun.state(), comprehensiveAnswer);
                assertEvidenceExpectation(
                        comprehensiveRun.scenario().evidenceExpectation(), comprehensiveRun.state(), comprehensiveEvidence);
                assertDeliveryRendering(comprehensiveRun.admission(), comprehensiveAnswer);

                recordingDeliveryTransport.clear();
                ScenarioRun seededRun = acceptAndProcess(seededScenario, testRunIdentity);
                PendingTerminalResponse.Answer seededAnswer = assertCompletedAcceptedState(
                        seededRun.state(), seededRun.admission());
                CitedEvidence seededEvidence = assertCitationsAndEvidence(seededRun.state(), seededAnswer);
                assertEvidenceExpectation(seededScenario.evidenceExpectation(), seededRun.state(), seededEvidence);
                assertDeliveryRendering(seededRun.admission(), seededAnswer);

                recordingDeliveryTransport.clear();
                ScenarioRun missingSymbolRun = acceptAndProcess(MISSING_SYMBOL_SCENARIO, testRunIdentity);
                PendingTerminalResponse.Answer missingSymbolAnswer = assertAcceptedInconclusive(
                        missingSymbolRun.state(), missingSymbolRun.admission());
                CitedEvidence missingSymbolEvidence = assertOptionalCitationsAndEvidence(
                        missingSymbolRun.state(), missingSymbolAnswer);
                assertDeliveryRendering(missingSymbolRun.admission(), missingSymbolAnswer);

                writeManifestIfRequested(seed, seededScenario, List.of(comprehensiveRun, seededRun, missingSymbolRun),
                        comprehensiveEvidence, seededEvidence, missingSymbolEvidence, agentSourceSha, semanticSourceSha);
            } finally {
                releaseM7LiveTestLock(advisoryLockConnection);
            }
        }
    }

    private PendingTerminalResponse.Answer assertCompletedAcceptedState(AgentRunState state, SourceAdmission admission) {
        assertThat(state.runId()).isEqualTo(admission.runId());
        assertThat(state.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(state.finalOutcome()).contains(RunOutcome.COMPLETED);
        assertThat(state.currentAttempt().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
        assertThat(state.budget().maxAgentSteps()).isEqualTo(12);
        assertThat(state.budget().maxQueryExecutions()).isEqualTo(12);
        assertThat(state.budget().usedQueryExecutions()).isLessThanOrEqualTo(12);
        assertThat(state.currentAttempt().issuedCandidates()).isNotEmpty();
        assertThat(state.currentAttempt().issuedEvidence()).isNotEmpty();
        state.currentAttempt().issuedCandidates().forEach((handle, issued) -> {
            assertThat(handle.binding().runId()).isEqualTo(state.runId());
            assertThat(handle.binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
            assertThat(handle.binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
        });
        assertSelectedQueryCandidateScope(state);
        state.currentAttempt().issuedEvidence().forEach((handle, issued) -> {
            assertThat(handle.binding().runId()).isEqualTo(state.runId());
            assertThat(handle.binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
            assertThat(handle.binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
            assertThat(issued.evidence().repositoryId()).isEqualTo(REPOSITORY_ID);
            assertThat(issued.evidence().repositoryRevision()).isEqualTo(REPOSITORY_REVISION);
        });
        PendingTerminalResponse response = state.pendingTerminalResponse()
                .orElseThrow(() -> new AssertionError("completed run did not retain its accepted response"));
        assertThat(response).isInstanceOf(PendingTerminalResponse.Answer.class);
        PendingTerminalResponse.Answer acceptedAnswer = (PendingTerminalResponse.Answer) response;
        assertThat(acceptedAnswer.acceptance().verdict()).hasValueSatisfying(verdict ->
                assertThat(verdict.disposition()).isEqualTo(AnswerDisposition.ACCEPTED_COMPLETE));
        return acceptedAnswer;
    }

    private void assertSelectedQueryCandidateScope(AgentRunState state) {
        Map<String, IssuedCandidate> candidatesByHandle = new LinkedHashMap<>();
        state.currentAttempt().issuedCandidates().forEach((handle, issued) ->
                candidatesByHandle.put(handle.value(), issued));
        List<IssuedCandidate> selectedQueryCandidates = state.modelInteractions().stream()
                .filter(interaction -> interaction instanceof ModelInteraction.ActionSelected)
                .map(interaction -> (ModelInteraction.ActionSelected) interaction)
                .filter(selected -> selected.action() instanceof QueryAction)
                .map(selected -> (QueryAction) selected.action())
                .flatMap(query -> query.candidates().stream())
                .map(reference -> Optional.ofNullable(candidatesByHandle.get(reference.value()))
                        .orElseThrow(() -> new AssertionError(
                                "selected query candidate did not resolve to issued context: " + reference.value())))
                .toList();
        assertThat(selectedQueryCandidates).isNotEmpty().allSatisfy(issued -> {
            assertThat(issued.candidate().repositoryId()).isEqualTo(REPOSITORY_ID);
            issued.candidate().repositoryRevision().ifPresent(revision ->
                    assertThat(revision).isEqualTo(REPOSITORY_REVISION));
        });
    }

    private CitedEvidence assertCitationsAndEvidence(
            AgentRunState state,
            PendingTerminalResponse.Answer acceptedAnswer) {
        CitedEvidence citedEvidence = citedEvidence(state, acceptedAnswer);
        assertThat(citedEvidence.handles()).isNotEmpty();
        return citedEvidence;
    }

    private CitedEvidence assertOptionalCitationsAndEvidence(
            AgentRunState state,
            PendingTerminalResponse.Answer acceptedAnswer) {
        return citedEvidence(state, acceptedAnswer);
    }

    private CitedEvidence citedEvidence(AgentRunState state, PendingTerminalResponse.Answer acceptedAnswer) {
        Map<String, IssuedEvidence> issuedEvidenceByHandle = new LinkedHashMap<>();
        state.currentAttempt().issuedEvidence().forEach((handle, issued) ->
                issuedEvidenceByHandle.put(handle.value(), issued));
        Set<String> citedHandleValues = new LinkedHashSet<>();
        acceptedAnswer.document().statements().forEach(statement ->
                statement.citations().forEach(citation -> citedHandleValues.add(citation.value())));
        List<IssuedEvidence> citedEvidence = citedHandleValues.stream()
                .map(handle -> Optional.ofNullable(issuedEvidenceByHandle.get(handle))
                        .orElseThrow(() -> new AssertionError("citation did not resolve to issued evidence: " + handle)))
                .toList();
        citedEvidence.forEach(issued -> {
            EvidenceHandle handle = issued.handle();
            assertThat(handle.binding().runId()).isEqualTo(state.runId());
            assertThat(handle.binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
            assertThat(handle.binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
            assertThat(issued.evidence().repositoryId()).isEqualTo(REPOSITORY_ID);
            assertThat(issued.evidence().repositoryRevision()).isEqualTo(REPOSITORY_REVISION);
            assertThat(issued.evidence().warnings()).allSatisfy(warning -> {
                String warningCode = warning.code().toUpperCase(Locale.ROOT);
                assertThat(warningCode).doesNotContain("PARTIAL", "TRUNCATED", "REVISION_MISMATCH");
                if (warningCode.contains("UNRESOLVED")) {
                    assertThat(warningCode).isEqualTo("DESCENDANT_CALL_UNRESOLVED");
                }
            });
        });
        CitedEvidence result = new CitedEvidence(Set.copyOf(citedHandleValues), citedEvidence);
        assertCitedEvidenceProvenance(state, result);
        return result;
    }

    private ScenarioRun acceptAndProcess(KnowledgeScenario scenario, String testRunIdentity)
            throws InterruptedException {
        String eventIdentity = testRunIdentity + "-" + scenario.id();
        SourceAcceptance acceptance = sourceAcceptance.accept(sourceEvent(scenario, eventIdentity, Instant.now()));
        assertThat(acceptance.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
        assertThat(acceptance.admission()).isPresent();
        SourceAdmission admission = acceptance.admission().orElseThrow();
        TerminalProcessing terminal = processUntilTerminal(admission, Instant.now().plus(TERMINAL_TIMEOUT));
        return new ScenarioRun(scenario, admission, terminal.state(), terminal.capacityDeferrals());
    }

    private TerminalProcessing processUntilTerminal(SourceAdmission admission, Instant deadline)
            throws InterruptedException {
        int capacityDeferrals = 0;
        while (Instant.now().isBefore(deadline)) {
            Instant processingTime = Instant.now();
            Optional<String> nextEligibleRunId = nextEligibleInboxRunId(processingTime);
            if (nextEligibleRunId.isEmpty()) {
                pauseForDurablePoll();
                continue;
            }
            String eligibleRunId = nextEligibleRunId.orElseThrow();
            if (!eligibleRunId.equals(admission.runId().value())) {
                throw new AssertionError("live database is not isolated: next eligible inbox run " + eligibleRunId
                        + " is not admitted run " + admission.runId().value());
            }
            InboxProcessingOutcome outcome = inboxProcessor.processNext(processingTime)
                    .orElseThrow(() -> new AssertionError(
                            "eligible inbox message disappeared before processing admitted run " + admission.runId().value()));
            switch (outcome) {
                case COMPLETED -> {
                    AgentRunState state = transitionPort.findByRunId(admission.runId())
                            .orElseThrow(() -> new AssertionError("accepted source did not persist its run state"));
                    if (state.status() == AgentRunStatus.CONCLUDED) {
                        return new TerminalProcessing(state, capacityDeferrals);
                    }
                    throw new AssertionError("completed inbox message left its admitted run nonterminal");
                }
                case CAPACITY_DEFERRED -> {
                    capacityDeferrals++;
                    pauseForDurablePoll();
                }
                case RETRY_SCHEDULED -> pauseForDurablePoll();
                case FAILED -> throw new AssertionError("inbox processing failed for admitted run " + admission.runId().value());
            }
        }
        throw new AssertionError("inbox processing did not reach a terminal state before " + deadline);
    }

    private void pauseForDurablePoll() throws InterruptedException {
        Thread.sleep(POLL_DELAY);
    }

    private PendingTerminalResponse.Answer assertAcceptedInconclusive(AgentRunState state, SourceAdmission admission) {
        assertThat(state.runId()).isEqualTo(admission.runId());
        assertThat(state.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(state.finalOutcome()).contains(RunOutcome.INCONCLUSIVE);
        PendingTerminalResponse response = state.pendingTerminalResponse()
                .orElseThrow(() -> new AssertionError("inconclusive run did not retain its accepted response"));
        assertThat(response).isInstanceOf(PendingTerminalResponse.Answer.class);
        PendingTerminalResponse.Answer answer = (PendingTerminalResponse.Answer) response;
        assertThat(answer.acceptance().verdict()).hasValueSatisfying(verdict ->
                assertThat(verdict.disposition()).isEqualTo(AnswerDisposition.ACCEPTED_INCONCLUSIVE));
        assertThat(answer.document().statements()).extracting(AnswerStatement::type)
                .containsAnyOf(StatementType.UNCERTAINTY, StatementType.LIMITATION)
                .doesNotContain(StatementType.FACT);
        return answer;
    }

    private void assertEvidenceExpectation(
            EvidenceExpectation expectation,
            AgentRunState state,
            CitedEvidence citedEvidence) {
        state.currentAttempt().issuedCapabilities().forEach((handle, capability) -> {
            assertThat(handle.binding().runId()).isEqualTo(state.runId());
            assertThat(handle.binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
            assertThat(handle.binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
            assertThat(capability.version()).isNotBlank();
        });
        assertThat(state.currentAttempt().issuedCandidates()).isNotEmpty();
        List<EvidenceCapabilityProvenance> citedProvenance = citedEvidenceProvenance(state, citedEvidence);
        assertThat(citedProvenance).extracting(provenance -> provenance.evidenceHandle().value())
                .containsAll(citedEvidence.handles());
        expectation.requiredCitedCapabilities().forEach(requiredCapability ->
                assertThat(citedProvenance.stream()
                        .map(EvidenceCapabilityProvenance::capability)
                        .anyMatch(requiredCapability::matches))
                        .as("cited evidence must directly originate from capability %s@%s",
                                requiredCapability.name(), requiredCapability.version())
                        .isTrue());
        expectation.assertSatisfiedBy(citedEvidence.evidence());
    }

    private void assertCitedEvidenceProvenance(AgentRunState state, CitedEvidence citedEvidence) {
        List<EvidenceCapabilityProvenance> citedProvenance = citedEvidenceProvenance(state, citedEvidence);
        assertThat(citedProvenance).extracting(provenance -> provenance.evidenceHandle().value())
                .containsExactlyInAnyOrderElementsOf(citedEvidence.handles());
        citedProvenance.forEach(provenance -> {
            assertThat(provenance.evidenceHandle().binding().runId()).isEqualTo(state.runId());
            assertThat(provenance.evidenceHandle().binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
            assertThat(provenance.evidenceHandle().binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
            assertThat(provenance.capability().version()).isNotBlank();
        });
    }

    private List<EvidenceCapabilityProvenance> citedEvidenceProvenance(AgentRunState state, CitedEvidence citedEvidence) {
        return EvidenceCapabilityProvenance.resolve(
                        state.currentAttempt().issuedCapabilities(),
                        state.currentAttempt().issuedEvidence(),
                        state.modelInteractions())
                .stream()
                .filter(provenance -> citedEvidence.handles().contains(provenance.evidenceHandle().value()))
                .toList();
    }

    private static void assertCitedEvidenceRelationships(List<IssuedEvidence> citedEvidence) {
        IssuedEvidence apiEntryPoint = requireCitedEvidence(citedEvidence,
                "entryPoint;", "kind=API", "class=com.example.orders.OrderController", "method=submitOrder",
                "url=/orders", "httpMethods=POST");
        IssuedEvidence scheduleEntryPoint = requireCitedEvidence(citedEvidence,
                "entryPoint;", "kind=SCHEDULE", "class=com.example.orders.OrderRecoveryJob",
                "method=retryPendingOrders");
        IssuedEvidence rabbitEntryPoint = requireCitedEvidence(citedEvidence,
                "entryPoint;", "kind=MQ", "class=com.example.orders.OrderMessageListener",
                "method=onOrderRequested", "broker=RABBIT");
        assertThat(Set.of(apiEntryPoint.handle().value(), scheduleEntryPoint.handle().value(),
                rabbitEntryPoint.handle().value())).hasSize(3);

        assertCitedGraphRelationship(citedEvidence, "OrderController", "submitOrder", "DefaultOrderWorkflow");
        assertCitedGraphRelationship(citedEvidence, "OrderRecoveryJob", "retryPendingOrders", "DefaultOrderWorkflow");
        assertCitedGraphRelationship(citedEvidence, "OrderMessageListener", "onOrderRequested", "DefaultOrderWorkflow");
        requireCitedEvidence(citedEvidence,
                "methodImplementation;", "requested=", "OrderWorkflow.processOrder(", "implementation=",
                "DefaultOrderWorkflow.processOrder(");
        boolean hasWorkflowInternalReference = citedEvidence.stream()
                .map(issued -> issued.evidence().content())
                .anyMatch(content -> content.contains("internalReference;")
                        && content.contains("context=METHOD:")
                        && (content.contains("OrderWorkflow.processOrder(")
                        || content.contains("DefaultOrderWorkflow.processOrder(")));
        assertThat(hasWorkflowInternalReference)
                .as("cited internal-reference evidence relevant to the shared workflow or implementation")
                .isTrue();
        boolean hasCompleteWorkflowMethodSource = citedEvidence.stream()
                .anyMatch(issued -> issued.evidence().semanticTarget().key().endsWith("DefaultOrderWorkflow.java")
                        && issued.evidence().content().contains("public void processOrder(OrderRequest request)")
                        && issued.evidence().content().contains("repository.save(request)"));
        assertThat(hasCompleteWorkflowMethodSource)
                .as("cited complete DefaultOrderWorkflow.processOrder source including repository.save")
                .isTrue();
    }

    private static IssuedEvidence requireCitedEvidence(List<IssuedEvidence> citedEvidence, String... requiredParts) {
        return citedEvidence.stream()
                .filter(issued -> containsAll(issued.evidence().content(), requiredParts))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing cited evidence containing " + List.of(requiredParts)));
    }

    private static boolean containsAll(String content, String... requiredParts) {
        for (String requiredPart : requiredParts) {
            if (!content.contains(requiredPart)) {
                return false;
            }
        }
        return true;
    }

    private static void assertCitedGraphRelationship(
            List<IssuedEvidence> citedEvidence,
            String callerClass,
            String callerMethod,
            String calleeClass) {
        boolean relationshipFound = citedEvidence.stream()
                .anyMatch(issued -> containsGraphRelationship(issued.evidence().content(), callerClass, callerMethod,
                        calleeClass, "processOrder"));
        assertThat(relationshipFound)
                .as("cited graph evidence connects %s.%s to %s.processOrder", callerClass, callerMethod, calleeClass)
                .isTrue();
    }

    private static boolean containsGraphRelationship(String content, String callerClass, String callerMethod,
                                              String calleeClass, String calleeMethod) {
        Map<String, String> targetsByNodeId = new LinkedHashMap<>();
        Matcher nodeMatcher = GRAPH_NODE_PATTERN.matcher(content);
        while (nodeMatcher.find()) {
            targetsByNodeId.put(nodeMatcher.group(1), nodeMatcher.group(2));
        }
        Matcher edgeMatcher = GRAPH_EDGE_PATTERN.matcher(content);
        while (edgeMatcher.find()) {
            String callerTarget = targetsByNodeId.get(edgeMatcher.group(1));
            String calleeTarget = targetsByNodeId.get(edgeMatcher.group(2));
            if (matchesGraphTarget(callerTarget, callerClass, callerMethod)
                    && matchesGraphTarget(calleeTarget, calleeClass, calleeMethod)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesGraphTarget(String target, String className, String methodName) {
        return Optional.ofNullable(target)
                .map(value -> value.endsWith("." + methodName)
                        && (value.contains("#" + className + ".")
                        || value.contains("." + className + ".")))
                .orElse(false);
    }

    private void assertDeliveryRendering(SourceAdmission admission, PendingTerminalResponse.Answer acceptedAnswer) {
        drainDeliveryOutbox(admission);
        List<DeliveryMessage> deliveries = recordingDeliveryTransport.deliveries();
        assertThat(deliveries).extracting(DeliveryMessage::kind)
                .containsExactly(DeliveryKind.RECEIPT, DeliveryKind.FINAL_RESPONSE);
        DeliveryMessage finalDelivery = deliveries.stream()
                .filter(delivery -> delivery.kind().equals(DeliveryKind.FINAL_RESPONSE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("recording transport did not receive the final response"));
        assertThat(finalDelivery.responseText()).isEqualTo(acceptedAnswer.document().renderParagraphs());
        List<DeliveryOutboxState> outboxStates = jdbcClient.sql("""
                SELECT delivery_kind, status
                FROM delivery_outbox
                WHERE analysis_run_id = :runId
                ORDER BY created_at, delivery_id
                """)
                .param("runId", admission.runId().value())
                .query((resultSet, rowNumber) -> new DeliveryOutboxState(
                        DeliveryKind.valueOf(resultSet.getString("delivery_kind")),
                        DeliveryStatus.valueOf(resultSet.getString("status"))))
                .list();
        assertThat(outboxStates).containsExactly(
                new DeliveryOutboxState(DeliveryKind.RECEIPT, DeliveryStatus.DELIVERED),
                new DeliveryOutboxState(DeliveryKind.FINAL_RESPONSE, DeliveryStatus.DELIVERED));
    }

    private void drainDeliveryOutbox(SourceAdmission admission) {
        while (true) {
            Instant processingTime = Instant.now();
            Optional<String> nextEligibleRunId = nextEligibleDeliveryRunId(processingTime);
            if (nextEligibleRunId.isEmpty()) {
                return;
            }
            String eligibleRunId = nextEligibleRunId.orElseThrow();
            if (!eligibleRunId.equals(admission.runId().value())) {
                throw new AssertionError("live database is not isolated: next eligible delivery run " + eligibleRunId
                        + " is not admitted run " + admission.runId().value());
            }
            Optional<DeliveryProcessingOutcome> outcome = deliveryProcessor.processNext(processingTime);
            if (outcome.isEmpty()) {
                throw new AssertionError("eligible delivery disappeared before processing admitted run "
                        + admission.runId().value());
            }
            assertThat(outcome).contains(DeliveryProcessingOutcome.DELIVERED);
        }
    }

    private void writeManifestIfRequested(
            long seed,
            KnowledgeScenario seededScenario,
            List<ScenarioRun> scenarioRuns,
            CitedEvidence comprehensiveEvidence,
            CitedEvidence seededEvidence,
            CitedEvidence missingSymbolEvidence,
            String agentSourceSha,
            String semanticSourceSha) throws IOException {
        Optional<Path> reportDirectory = Optional.ofNullable(System.getenv("M7_REPORT_DIRECTORY"))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .filter(Files::isDirectory);
        if (reportDirectory.isEmpty()) {
            return;
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("seed", seed);
        manifest.put("selectedScenario", seededScenario.id());
        manifest.put("agentSourceSha", agentSourceSha);
        manifest.put("semanticSourceSha", semanticSourceSha);
        manifest.put("fixtureRevision", REPOSITORY_REVISION.value());
        manifest.put("modelName", Optional.ofNullable(environment.getProperty("spring.ai.google.genai.chat.model"))
                .orElse("unknown"));
        manifest.put("catalogDigest", promptResourceCatalog.catalogDigest());
        manifest.put("totalCapacityDeferrals", scenarioRuns.stream()
                .mapToInt(ScenarioRun::capacityDeferrals)
                .sum());
        manifest.put("runs", List.of(
                reportRun(scenarioRuns.get(0), comprehensiveEvidence),
                reportRun(scenarioRuns.get(1), seededEvidence),
                reportRun(scenarioRuns.get(2), missingSymbolEvidence)));
        Path manifestPath = reportDirectory.orElseThrow()
                .resolve("m7-knowledge-query-manifest-" + seed + "-"
                        + scenarioRuns.getFirst().admission().runId().value() + ".json");
        Files.writeString(manifestPath, objectMapper.writeValueAsString(manifest), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private Map<String, Object> reportRun(ScenarioRun scenarioRun, CitedEvidence citedEvidence) {
        AgentRunState state = scenarioRun.state();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scenario", scenarioRun.scenario().id());
        metadata.put("sessionId", scenarioRun.admission().sessionId().value());
        metadata.put("runId", scenarioRun.admission().runId().value());
        metadata.put("outcome", state.finalOutcome().orElseThrow().name());
        metadata.put("acceptedDisposition", acceptedDisposition(state));
        List<Map<String, String>> citedEvidenceProvenance = citedEvidence.evidence().stream()
                .map(issued -> Map.of(
                        "handle", issued.handle().value(),
                        "capability", citedEvidenceCapability(state, issued.handle()),
                        "source", issued.evidence().sourceService(),
                        "repository", issued.evidence().repositoryId().value(),
                        "revision", issued.evidence().repositoryRevision().value()))
                .toList();
        metadata.put("citedCapabilities", citedEvidenceProvenance.stream()
                .map(provenance -> provenance.get("capability"))
                .distinct()
                .sorted()
                .toList());
        metadata.put("citedEvidenceProvenance", citedEvidenceProvenance);
        return Map.copyOf(metadata);
    }

    private String citedEvidenceCapability(AgentRunState state, EvidenceHandle evidenceHandle) {
        return EvidenceCapabilityProvenance.resolve(
                        state.currentAttempt().issuedCapabilities(),
                        state.currentAttempt().issuedEvidence(),
                        state.modelInteractions())
                .stream()
                .filter(provenance -> provenance.evidenceHandle().equals(evidenceHandle))
                .map(EvidenceCapabilityProvenance::capability)
                .map(capability -> capability.name() + "@" + capability.version())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "cited evidence does not resolve to issued capability provenance: " + evidenceHandle.value()));
    }

    private String acceptedDisposition(AgentRunState state) {
        return state.pendingTerminalResponse()
                .filter(PendingTerminalResponse.Answer.class::isInstance)
                .map(PendingTerminalResponse.Answer.class::cast)
                .flatMap(answer -> answer.acceptance().verdict())
                .map(verdict -> verdict.disposition().name())
                .orElse("CONTRACT_ONLY");
    }

    private void clearTestOwnedRows() {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            List<String> sourceTypes = List.of(SOURCE_TYPE, LEGACY_SOURCE_TYPE);
            List<String> sessionIds = resolveTestOwnedIds("""
                    SELECT session_id
                    FROM agent_session
                    WHERE source_type IN (:sourceTypes)
                    """, sourceTypes);
            List<String> inboxMessageIds = resolveTestOwnedIds("""
                    SELECT inbox_message_id
                    FROM session_inbox
                    WHERE source_type IN (:sourceTypes)
                    """, sourceTypes);
            List<String> runIds = sessionIds.isEmpty()
                    ? List.of()
                    : resolveIds("SELECT run_id FROM agent_run WHERE session_id IN (:ids)", sessionIds);
            List<String> deliveryIds = resolveTestOwnedIds("""
                    SELECT delivery_id
                    FROM delivery_outbox
                    WHERE source_type IN (:sourceTypes)
                    """, sourceTypes);
            List<String> conflictIds = resolveTestOwnedIds("""
                    SELECT conflict_id
                    FROM source_event_conflict
                    WHERE source_type IN (:sourceTypes)
                    """, sourceTypes);
            List<String> canonicalMessageIds = resolveTestOwnedIds("""
                    SELECT source_message_id
                    FROM canonical_source_message
                    WHERE source_type IN (:sourceTypes)
                    """, sourceTypes);
            List<String> transportEventIds = resolveTestOwnedIds("""
                    SELECT transport_event_id
                    FROM source_transport_event
                    WHERE source_type IN (:sourceTypes)
                    """, sourceTypes);

            deleteResolvedIds("DELETE FROM delivery_outbox WHERE delivery_id IN (:ids)", deliveryIds);
            deleteResolvedIds("DELETE FROM agent_run_event WHERE run_id IN (:ids)", runIds);
            deleteResolvedIds("DELETE FROM session_turn WHERE session_id IN (:ids)", sessionIds);
            deleteResolvedIds("DELETE FROM agent_run WHERE run_id IN (:ids)", runIds);
            deleteResolvedIds("DELETE FROM session_inbox WHERE inbox_message_id IN (:ids)", inboxMessageIds);
            deleteResolvedIds("DELETE FROM agent_session WHERE session_id IN (:ids)", sessionIds);
            deleteResolvedIds("DELETE FROM source_event_conflict WHERE conflict_id IN (:ids)", conflictIds);
            deleteResolvedSourceRows("""
                    DELETE FROM canonical_source_message
                    WHERE source_type IN (:sourceTypes)
                      AND source_message_id IN (:ids)
                    """, sourceTypes, canonicalMessageIds);
            deleteResolvedSourceRows("""
                    DELETE FROM source_transport_event
                    WHERE source_type IN (:sourceTypes)
                      AND transport_event_id IN (:ids)
                    """, sourceTypes, transportEventIds);
        });
    }

    private List<String> resolveTestOwnedIds(String sql, List<String> sourceTypes) {
        return jdbcClient.sql(sql)
                .param("sourceTypes", sourceTypes)
                .query(String.class)
                .list();
    }

    private List<String> resolveIds(String sql, List<String> ids) {
        return jdbcClient.sql(sql)
                .param("ids", ids)
                .query(String.class)
                .list();
    }

    private void deleteResolvedIds(String sql, List<String> ids) {
        if (!ids.isEmpty()) {
            jdbcClient.sql(sql)
                    .param("ids", ids)
                    .update();
        }
    }

    private void deleteResolvedSourceRows(String sql, List<String> sourceTypes, List<String> ids) {
        if (!ids.isEmpty()) {
            jdbcClient.sql(sql)
                    .param("sourceTypes", sourceTypes)
                    .param("ids", ids)
                    .update();
        }
    }

    private void assertNoEligibleInboxBeforeSubmission() {
        assertThat(nextEligibleInboxRunId(Instant.now()))
                .as("live database must have no eligible inbox row before M7 submissions")
                .isEmpty();
    }

    private Optional<String> nextEligibleInboxRunId(Instant now) {
        return jdbcClient.sql("""
                SELECT inbox.analysis_run_id
                FROM session_inbox inbox
                JOIN agent_session session ON session.session_id = inbox.session_id
                WHERE inbox.status = 'PENDING'
                  AND inbox.available_at <= :now
                  AND NOT EXISTS (
                      SELECT 1
                      FROM session_inbox processing
                      WHERE processing.status = 'PROCESSING'
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM session_inbox earlier
                      WHERE earlier.session_id = inbox.session_id
                        AND earlier.session_sequence < inbox.session_sequence
                        AND earlier.status IN ('PENDING', 'PROCESSING')
                  )
                ORDER BY inbox.available_at, inbox.created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """)
                .param("now", Timestamp.from(now))
                .query(String.class)
                .optional();
    }

    private Optional<String> nextEligibleDeliveryRunId(Instant now) {
        return jdbcClient.sql("""
                SELECT analysis_run_id
                FROM delivery_outbox
                WHERE status = 'PENDING'
                   OR (status = 'RETRY_SCHEDULED' AND next_attempt_at <= :now)
                ORDER BY next_attempt_at, created_at
                LIMIT 1
                """)
                .param("now", Timestamp.from(now))
                .query(String.class)
                .optional();
    }

    private void acquireM7LiveTestLock(Connection advisoryLockConnection) throws SQLException {
        try (PreparedStatement statement = advisoryLockConnection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, M7_LIVE_ADVISORY_LOCK_KEY);
            statement.execute();
        }
    }

    private void releaseM7LiveTestLock(Connection advisoryLockConnection) throws SQLException {
        try (PreparedStatement statement = advisoryLockConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, M7_LIVE_ADVISORY_LOCK_KEY);
            statement.execute();
        }
    }

    private NormalizedSourceEvent sourceEvent(KnowledgeScenario scenario, String eventIdentity, Instant receivedAt) {
        return new NormalizedSourceEvent(
                SOURCE_TYPE,
                new TransportEventId(eventIdentity),
                new SourceMessageId(eventIdentity),
                new SessionSourceRef(SOURCE_TYPE, eventIdentity),
                new ParticipantRef(SOURCE_TYPE, eventIdentity + "-participant"),
                scenario.question(),
                scenario.question(),
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        SOURCE_TYPE, eventIdentity + "-participant", eventIdentity, eventIdentity,
                        eventIdentity, scenario.question()),
                receivedAt);
    }

    private static String requiredEnvironment(String name) {
        return Optional.ofNullable(System.getenv(name))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("required environment variable is not set: " + name));
    }

    private static String requiredSourceSha(String name) {
        String sourceSha = requiredEnvironment(name);
        if (!SOURCE_SHA.matcher(sourceSha).matches()) {
            throw new IllegalStateException("required environment variable must be a lowercase 40-character source SHA: " + name);
        }
        return sourceSha;
    }

    private record KnowledgeScenario(String id, String question, EvidenceExpectation evidenceExpectation) {
    }

    private record ScenarioRun(KnowledgeScenario scenario, SourceAdmission admission, AgentRunState state,
                               int capacityDeferrals) {
    }

    private record TerminalProcessing(AgentRunState state, int capacityDeferrals) {
    }

    private record CitedEvidence(Set<String> handles, List<IssuedEvidence> evidence) {
    }

    private record DeliveryOutboxState(DeliveryKind kind, DeliveryStatus status) {
    }

    private enum EvidenceExpectation {
        COMPREHENSIVE(Set.of(
                new CapabilityIdentity("codebase_list_entry_points", "v1"),
                new CapabilityIdentity("codebase_outgoing_call_graph", "v1"),
                new CapabilityIdentity("codebase_discover_method_implementations", "v1"),
                new CapabilityIdentity("codebase_find_internal_references", "v1"),
                new CapabilityIdentity("codebase_get_method_source", "v1"))) {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                assertCitedEvidenceRelationships(citedEvidence);
            }
        },
        HTTP_TO_WORKFLOW(Set.of(
                new CapabilityIdentity("codebase_list_entry_points", "v1"),
                new CapabilityIdentity("codebase_outgoing_call_graph", "v1"))) {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                requireCitedEvidence(citedEvidence, "entryPoint;", "kind=API",
                        "class=com.example.orders.OrderController", "method=submitOrder");
                assertCitedGraphRelationship(citedEvidence, "OrderController", "submitOrder", "DefaultOrderWorkflow");
            }
        },
        SCHEDULE_TO_WORKFLOW(Set.of(
                new CapabilityIdentity("codebase_list_entry_points", "v1"),
                new CapabilityIdentity("codebase_outgoing_call_graph", "v1"))) {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                requireCitedEvidence(citedEvidence, "entryPoint;", "kind=SCHEDULE",
                        "class=com.example.orders.OrderRecoveryJob", "method=retryPendingOrders");
                assertCitedGraphRelationship(citedEvidence, "OrderRecoveryJob", "retryPendingOrders",
                        "DefaultOrderWorkflow");
            }
        },
        MESSAGE_TO_WORKFLOW(Set.of(
                new CapabilityIdentity("codebase_list_entry_points", "v1"),
                new CapabilityIdentity("codebase_outgoing_call_graph", "v1"))) {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                requireCitedEvidence(citedEvidence, "entryPoint;", "kind=MQ",
                        "class=com.example.orders.OrderMessageListener", "method=onOrderRequested", "broker=RABBIT");
                assertCitedGraphRelationship(citedEvidence, "OrderMessageListener", "onOrderRequested",
                        "DefaultOrderWorkflow");
            }
        },
        IMPLEMENTATION_AND_SOURCE(Set.of(
                new CapabilityIdentity("codebase_discover_method_implementations", "v1"),
                new CapabilityIdentity("codebase_find_internal_references", "v1"),
                new CapabilityIdentity("codebase_get_method_source", "v1"))) {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                requireCitedEvidence(citedEvidence, "methodImplementation;", "requested=", "OrderWorkflow.processOrder(",
                        "implementation=", "DefaultOrderWorkflow.processOrder(");
                boolean hasWorkflowDeclarationReference = citedEvidence.stream()
                        .map(issued -> issued.evidence().content())
                        .anyMatch(content -> content.contains("internalReference;")
                                && content.contains("context=METHOD:")
                                && (content.contains("OrderWorkflow.processOrder(")
                                || content.contains("DefaultOrderWorkflow.processOrder(")));
                assertThat(hasWorkflowDeclarationReference)
                        .as("cited workflow declaration or implementation reference")
                        .isTrue();
                boolean hasCompleteMethodSource = citedEvidence.stream()
                        .anyMatch(issued -> issued.evidence().semanticTarget().key().endsWith("DefaultOrderWorkflow.java")
                                && issued.evidence().content().contains("public void processOrder(OrderRequest request)")
                                && issued.evidence().content().contains("repository.save(request)"));
                assertThat(hasCompleteMethodSource).as("cited complete workflow method source").isTrue();
            }
        },
        MISSING_SYMBOL(Set.of()) {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                throw new UnsupportedOperationException("missing symbol assertions are run-outcome based");
            }
        };

        private final Set<CapabilityIdentity> requiredCitedCapabilities;

        EvidenceExpectation(Set<CapabilityIdentity> requiredCitedCapabilities) {
            this.requiredCitedCapabilities = Set.copyOf(requiredCitedCapabilities);
        }

        Set<CapabilityIdentity> requiredCitedCapabilities() {
            return requiredCitedCapabilities;
        }

        abstract void assertSatisfiedBy(List<IssuedEvidence> citedEvidence);
    }

    private record CapabilityIdentity(String name, String version) {

        boolean matches(CapabilityPolicy capability) {
            return name.equals(capability.name()) && version.equals(capability.version());
        }
    }

    /**
     * 補足 agent-runtime 手動測試所需的 delivery 邊界，不變更其他 runtime 組裝
     */
    @TestConfiguration(proxyBeanMethods = false)
    @Profile("m7-knowledge-live")
    static class DeliveryTestConfiguration {

        @Bean
        RecordingDeliveryTransport deliveryTransportPort() {
            return new RecordingDeliveryTransport();
        }

        @Bean
        DeliveryRetryPolicy deliveryRetryPolicy() {
            return new DeliveryRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), attempt -> Duration.ZERO,
                    DeliveryRetryPolicy.DEFAULT_MAXIMUM_ATTEMPTS);
        }

        @Bean
        DeliveryProcessor deliveryProcessor(
                DeliveryOutboxPort deliveryOutboxPort,
                DeliveryTransportPort deliveryTransportPort,
                DeliveryRetryPolicy retryPolicy) {
            return new DeliveryProcessor(deliveryOutboxPort, deliveryTransportPort, retryPolicy);
        }

        @Bean
        DeliveryWorkApplicationService deliveryWorkApplicationService(
                DeliveryOutboxPort deliveryOutboxPort,
                DeliveryProcessor deliveryProcessor,
                ClaimAdmissionCoordinator claimAdmissionCoordinator) {
            return new DeliveryWorkApplicationService(deliveryOutboxPort, deliveryProcessor, claimAdmissionCoordinator);
        }
    }

    /**
     * 記錄 normal delivery 內容並回覆 transport 已送達的 thread-safe test boundary
     */
    static final class RecordingDeliveryTransport implements DeliveryTransportPort {

        private final List<DeliveryMessage> delivered = new CopyOnWriteArrayList<>();

        @Override
        public DeliveryTransportResult deliver(DeliveryMessage message, Instant now) {
            delivered.add(message);
            return new DeliveryTransportResult.Delivered("live-test-" + message.deliveryId().value());
        }

        List<DeliveryMessage> deliveries() {
            return List.copyOf(delivered);
        }

        void clear() {
            delivered.clear();
        }
    }
}
