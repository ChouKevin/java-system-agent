package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final Duration POLL_DELAY = Duration.ofSeconds(2);
    private static final Duration TERMINAL_TIMEOUT = Duration.ofMinutes(8);
    private static final KnowledgeScenario COMPREHENSIVE_SCENARIO = new KnowledgeScenario(
            "comprehensive",
            "在 m7-knowledge-query repository 中，訂單處理有哪些 HTTP API、排程與訊息消費入口？請列出各入口的 package、class、method，分別用 outgoing call-graph evidence 說明它們如何進入共同的 OrderWorkflow.processOrder 流程，再用 implementation 與 internal-reference evidence 驗證共同處理實作，並提供該實作方法的完整來源。",
            EvidenceExpectation.IMPLEMENTATION_AND_SOURCE);
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

    @Test
    void should_accept_complete_and_inconclusive_m7_knowledge_queries_through_the_normal_runtime()
            throws IOException, InterruptedException {
        long seed = Long.parseLong(requiredEnvironment("M7_KNOWLEDGE_SEED"));
        KnowledgeScenario seededScenario = SEEDED_SCENARIOS.get(new Random(seed).nextInt(SEEDED_SCENARIOS.size()));
        String testRunIdentity = "m7-" + Instant.now().toEpochMilli() + "-"
                + System.getProperty("surefire.forkNumber", "default");
        clearTestOwnedRows();

        ScenarioRun comprehensiveRun = acceptAndProcess(COMPREHENSIVE_SCENARIO, testRunIdentity);
        PendingTerminalResponse.Answer comprehensiveAnswer = assertCompletedAcceptedState(
                comprehensiveRun.state(), comprehensiveRun.admission());
        CitedEvidence comprehensiveEvidence = assertCitationsAndEvidence(
                comprehensiveRun.state(), comprehensiveAnswer);
        assertCitedEvidenceRelationships(comprehensiveEvidence.evidence());

        ScenarioRun seededRun = acceptAndProcess(seededScenario, testRunIdentity);
        PendingTerminalResponse.Answer seededAnswer = assertCompletedAcceptedState(seededRun.state(), seededRun.admission());
        CitedEvidence seededEvidence = assertCitationsAndEvidence(seededRun.state(), seededAnswer);
        assertEvidenceExpectation(seededScenario.evidenceExpectation(), seededRun.state(), seededEvidence.evidence());

        ScenarioRun missingSymbolRun = acceptAndProcess(MISSING_SYMBOL_SCENARIO, testRunIdentity);
        assertAcceptedInconclusive(missingSymbolRun.state(), missingSymbolRun.admission());

        writeManifestIfRequested(seed, seededScenario, List.of(comprehensiveRun, seededRun, missingSymbolRun),
                comprehensiveEvidence, seededEvidence);
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
        Map<String, IssuedEvidence> issuedEvidenceByHandle = new LinkedHashMap<>();
        state.currentAttempt().issuedEvidence().forEach((handle, issued) ->
                issuedEvidenceByHandle.put(handle.value(), issued));
        Set<String> citedHandleValues = new LinkedHashSet<>();
        acceptedAnswer.document().statements().forEach(statement ->
                statement.citations().forEach(citation -> citedHandleValues.add(citation.value())));
        assertThat(citedHandleValues).isNotEmpty();

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

        return new CitedEvidence(Set.copyOf(citedHandleValues), citedEvidence);
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
            Optional<InboxProcessingOutcome> nextOutcome = inboxProcessor.processNext(Instant.now());
            if (nextOutcome.isEmpty()) {
                pauseForDurablePoll();
                continue;
            }
            InboxProcessingOutcome outcome = nextOutcome.orElseThrow();
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

    private void assertAcceptedInconclusive(AgentRunState state, SourceAdmission admission) {
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
    }

    private void assertEvidenceExpectation(
            EvidenceExpectation expectation,
            AgentRunState state,
            List<IssuedEvidence> citedEvidence) {
        assertThat(state.currentAttempt().issuedCapabilities().values())
                .isNotEmpty()
                .anySatisfy(capability -> assertThat(capability.name()).startsWith("codebase_"));
        state.currentAttempt().issuedCapabilities().forEach((handle, capability) -> {
            assertThat(handle.binding().runId()).isEqualTo(state.runId());
            assertThat(handle.binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
            assertThat(handle.binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
            assertThat(capability.version()).isNotBlank();
        });
        assertThat(state.currentAttempt().issuedCandidates()).isNotEmpty();
        expectation.assertSatisfiedBy(citedEvidence);
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

    private void assertDeliveryRendering(PendingTerminalResponse.Answer acceptedAnswer) {
        List<DeliveryMessage> deliveries = recordingDeliveryTransport.deliveries();
        assertThat(deliveries).extracting(DeliveryMessage::kind)
                .containsExactly(DeliveryKind.RECEIPT, DeliveryKind.FINAL_RESPONSE);
        DeliveryMessage finalDelivery = deliveries.stream()
                .filter(delivery -> delivery.kind().equals(DeliveryKind.FINAL_RESPONSE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("recording transport did not receive the final response"));
        assertThat(finalDelivery.responseText()).isEqualTo(acceptedAnswer.document().renderParagraphs());
    }

    private void writeManifestIfRequested(
            long seed,
            KnowledgeScenario seededScenario,
            List<ScenarioRun> scenarioRuns,
            CitedEvidence comprehensiveEvidence,
            CitedEvidence seededEvidence) throws IOException {
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
        manifest.put("agentRevision", environment.getProperty("git.commit.id", "unknown"));
        manifest.put("semanticRevision", REPOSITORY_REVISION.value());
        manifest.put("modelName", Optional.ofNullable(environment.getProperty("spring.ai.google.genai.chat.model"))
                .orElse("unknown"));
        manifest.put("catalogDigest", promptResourceCatalog.catalogDigest());
        manifest.put("totalCapacityDeferrals", scenarioRuns.stream()
                .mapToInt(ScenarioRun::capacityDeferrals)
                .sum());
        manifest.put("runs", List.of(
                reportRun(scenarioRuns.get(0), comprehensiveEvidence),
                reportRun(scenarioRuns.get(1), seededEvidence),
                reportRun(scenarioRuns.get(2), new CitedEvidence(Set.of(), List.of()))));
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
        metadata.put("capabilities", state.currentAttempt().issuedCapabilities().values().stream()
                .map(capability -> capability.name() + "@" + capability.version())
                .sorted()
                .toList());
        metadata.put("citedEvidence", citedEvidence.evidence().stream()
                .map(issued -> Map.of(
                        "handle", issued.handle().value(),
                        "source", issued.evidence().sourceService(),
                        "repository", issued.evidence().repositoryId().value(),
                        "revision", issued.evidence().repositoryRevision().value()))
                .toList());
        return Map.copyOf(metadata);
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
        for (String sourceType : List.of(SOURCE_TYPE, LEGACY_SOURCE_TYPE)) {
            jdbcClient.sql("DELETE FROM delivery_outbox WHERE source_type = :sourceType")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM agent_run_event WHERE run_id IN "
                            + "(SELECT analysis_run_id FROM session_inbox WHERE source_type = :sourceType)")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM session_turn WHERE session_id IN "
                            + "(SELECT session_id FROM agent_session WHERE source_type = :sourceType)")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM agent_run WHERE session_id IN "
                            + "(SELECT session_id FROM agent_session WHERE source_type = :sourceType)")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM session_inbox WHERE source_type = :sourceType")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM agent_session WHERE source_type = :sourceType")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM source_event_conflict WHERE source_type = :sourceType")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM canonical_source_message WHERE source_type = :sourceType")
                    .param("sourceType", sourceType)
                    .update();
            jdbcClient.sql("DELETE FROM source_transport_event WHERE source_type = :sourceType")
                    .param("sourceType", sourceType)
                    .update();
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

    private record KnowledgeScenario(String id, String question, EvidenceExpectation evidenceExpectation) {
    }

    private record ScenarioRun(KnowledgeScenario scenario, SourceAdmission admission, AgentRunState state,
                               int capacityDeferrals) {
    }

    private record TerminalProcessing(AgentRunState state, int capacityDeferrals) {
    }

    private record CitedEvidence(Set<String> handles, List<IssuedEvidence> evidence) {
    }

    private enum EvidenceExpectation {
        HTTP_TO_WORKFLOW {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                requireCitedEvidence(citedEvidence, "entryPoint;", "kind=API",
                        "class=com.example.orders.OrderController", "method=submitOrder");
                assertCitedGraphRelationship(citedEvidence, "OrderController", "submitOrder", "DefaultOrderWorkflow");
            }
        },
        SCHEDULE_TO_WORKFLOW {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                requireCitedEvidence(citedEvidence, "entryPoint;", "kind=SCHEDULE",
                        "class=com.example.orders.OrderRecoveryJob", "method=retryPendingOrders");
                assertCitedGraphRelationship(citedEvidence, "OrderRecoveryJob", "retryPendingOrders",
                        "DefaultOrderWorkflow");
            }
        },
        MESSAGE_TO_WORKFLOW {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                requireCitedEvidence(citedEvidence, "entryPoint;", "kind=MQ",
                        "class=com.example.orders.OrderMessageListener", "method=onOrderRequested", "broker=RABBIT");
                assertCitedGraphRelationship(citedEvidence, "OrderMessageListener", "onOrderRequested",
                        "DefaultOrderWorkflow");
            }
        },
        IMPLEMENTATION_AND_SOURCE {
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
        MISSING_SYMBOL {
            @Override
            void assertSatisfiedBy(List<IssuedEvidence> citedEvidence) {
                throw new UnsupportedOperationException("missing symbol assertions are run-outcome based");
            }
        };

        abstract void assertSatisfiedBy(List<IssuedEvidence> citedEvidence);
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
    }
}
