package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.plan.NeedResolution;
import com.java.system.agent.answering.domain.plan.NeedResolutionStatus;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.ActionResult;
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
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceAcceptance;
import com.java.system.agent.interaction.domain.SourceAcceptanceStatus;
import com.java.system.agent.interaction.domain.SourceAdmission;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.interaction.domain.TransportEventId;
import com.java.system.agent.interaction.port.in.AcceptSourceEventUseCase;
import com.java.system.agent.interaction.port.in.ProcessNextInboxUseCase;
import com.java.system.agent.model.prompt.PromptResourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live acceptance for the payment knowledge fixture.
 *
 * <p>The scenario-specific {@code PAYMENT_KNOWLEDGE_LIVE} gate remains intentional: Task 9 sets
 * it independently from {@code M7_KNOWLEDGE_LIVE}, while generic {@code KNOWLEDGE_*} variables
 * carry shared source-SHA and report metadata. The acceptance checks durable plan, resolution,
 * citation, provenance, and LLM-verification structure rather than question-specific prose or
 * fixture payment rules. It also deliberately uses no test-only claim transaction: the dedicated
 * database precondition is guarded by its connected catalog and foreign eligible rows still fail
 * fast before every production inbox processor call.
 */
@SpringBootTest
@ActiveProfiles("agent-runtime")
@EnabledIfEnvironmentVariable(named = "PAYMENT_KNOWLEDGE_LIVE", matches = "true")
class PaymentKnowledgeLiveIT {

    private static final String LIVE_DATABASE_CATALOG = "agent_knowledge_live";
    private static final String SOURCE_TYPE = "payment-knowledge-live";
    private static final String FIXTURE_ID = "payment-knowledge-query";
    private static final PaymentScenario KNOWN_SOURCE_SCENARIO = new PaymentScenario(
            "payment-options",
            "我們目前支援哪些付款方式？各自會收手續費嗎？如果費用會依條件不同，請一併說明。",
            ScenarioExpectation.KNOWN_SOURCE);
    private static final PaymentScenario RUNTIME_ONLY_SCENARIO = new PaymentScenario(
            "runtime-payment-options",
            "現在實際開放哪些付款方式？各方式目前的手續費是多少？若有通路暫停也請說明。",
            ScenarioExpectation.RUNTIME_ONLY);
    private static final PaymentScenario ABSENT_BUSINESS_SCENARIO = new PaymentScenario(
            "absent-buy-now-pay-later",
            "我們是否支援先買後付？額度、分期與逾期費用規則是什麼？",
            ScenarioExpectation.ABSENT_BUSINESS);
    private static final List<PaymentScenario> SCENARIOS = List.of(
            KNOWN_SOURCE_SCENARIO,
            RUNTIME_ONLY_SCENARIO,
            ABSENT_BUSINESS_SCENARIO);
    private static final RepositoryId REPOSITORY_ID = new RepositoryId(FIXTURE_ID);
    private static final RepositoryRevision REPOSITORY_REVISION = new RepositoryRevision("FIXTURE");
    private static final RevisionVector EXPECTED_REVISIONS = RevisionVector.fromEntries(
            List.of(new RevisionVector.Entry(REPOSITORY_ID, REPOSITORY_REVISION)));
    private static final Duration POLL_DELAY = Duration.ofSeconds(2);
    private static final Duration TERMINAL_TIMEOUT = Duration.ofMinutes(6);
    private static final Pattern SOURCE_SHA = Pattern.compile("[0-9a-f]{40}");

    @Autowired
    private AcceptSourceEventUseCase sourceAcceptance;

    @Autowired
    private ProcessNextInboxUseCase inboxProcessor;

    @Autowired
    private AgentTransitionPort transitionPort;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private PromptResourceCatalog promptResourceCatalog;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void answers_payment_knowledge_through_the_normal_durable_runtime()
            throws IOException, InterruptedException, SQLException {
        assertConnectedLiveDatabaseCatalog();
        Optional<Path> reportDirectory = reportDirectoryIfRequested();
        SourceShas sourceShas = requiredSourceShas();
        for (PaymentScenario scenario : SCENARIOS) {
            ScenarioRun run = acceptAndProcess(scenario);
            AcceptedState acceptedState = assertAcceptedRun(
                    run.admission(), run.terminal().state(), scenario.expectation());
            CitedEvidence citedEvidence = scenario.expectation() == ScenarioExpectation.KNOWN_SOURCE
                    ? assertCitedEvidence(run.terminal().state(), acceptedState.answer())
                    : assertOptionalCitedEvidence(run.terminal().state(), acceptedState.answer());
            List<NeedResolution> resolutions = assertPlanAndResolutionAuthority(
                    run.terminal().state(), acceptedState.plan(), acceptedState.answer(), citedEvidence);
            assertScenarioExpectation(
                    scenario.expectation(), run.terminal().state(), acceptedState.answer(), resolutions);
            assertCitedCapabilityProvenance(run.terminal().state(), citedEvidence);
            AcceptedRun acceptedRun = new AcceptedRun(
                    acceptedState.plan(), acceptedState.answer(), resolutions);
            writeReportIfRequested(
                    reportDirectory,
                    scenario,
                    run.admission(),
                    run.terminal(),
                    acceptedRun,
                    citedEvidence,
                    sourceShas);
        }
    }

    private ScenarioRun acceptAndProcess(PaymentScenario scenario) throws InterruptedException {
        assertNoEligibleInboxBeforeSubmission();
        String identity = "payment-knowledge-" + scenario.id() + "-" + UUID.randomUUID();
        SourceAcceptance acceptance = sourceAcceptance.accept(sourceEvent(scenario, identity, Instant.now()));
        assertThat(acceptance.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
        SourceAdmission admission = acceptance.admission().orElseThrow();
        TerminalProcessing terminal = processUntilTerminal(admission, Instant.now().plus(TERMINAL_TIMEOUT));
        return new ScenarioRun(scenario, admission, terminal);
    }

    private void assertConnectedLiveDatabaseCatalog() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            if (!LIVE_DATABASE_CATALOG.equals(catalog)) {
                throw new AssertionError("live test database catalog must be " + LIVE_DATABASE_CATALOG);
            }
        }
    }

    private TerminalProcessing processUntilTerminal(SourceAdmission admission, Instant deadline) throws InterruptedException {
        int capacityDeferrals = 0;
        while (Instant.now().isBefore(deadline)) {
            Instant processingTime = Instant.now();
            Optional<String> eligibleRunId = nextEligibleInboxRunId(processingTime);
            if (eligibleRunId.isEmpty()) {
                pauseForDurablePoll();
                continue;
            }
            String selectedRunId = eligibleRunId.orElseThrow();
            if (!selectedRunId.equals(admission.runId().value())) {
                throw new AssertionError("foreign eligible inbox row exists before processing the admitted payment run");
            }
            InboxProcessingOutcome outcome = inboxProcessor.processNext(processingTime)
                    .orElseThrow(() -> new AssertionError("admitted inbox row disappeared before processing"));
            switch (outcome) {
                case COMPLETED -> {
                    AgentRunState state = transitionPort.findByRunId(admission.runId())
                            .orElseThrow(() -> new AssertionError("admitted source did not persist a run state"));
                    if (state.status() == AgentRunStatus.CONCLUDED) {
                        return new TerminalProcessing(state, capacityDeferrals);
                    }
                    throw new AssertionError("completed inbox row left the admitted run nonterminal");
                }
                case CAPACITY_DEFERRED -> {
                    capacityDeferrals++;
                    pauseForDurablePoll();
                }
                case RETRY_SCHEDULED -> throw new AssertionError("live test refuses an automatic retry after external failure");
                case FAILED -> throw new AssertionError("inbox processing failed for the admitted payment run");
            }
        }
        throw new AssertionError("payment knowledge run did not reach a terminal state before the bounded deadline");
    }

    private AcceptedState assertAcceptedRun(
            SourceAdmission admission,
            AgentRunState state,
            ScenarioExpectation expectation) {
        assertThat(state.runId()).isEqualTo(admission.runId());
        assertThat(state.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(state.currentAttempt().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
        QuestionPlan plan = state.questionPlan()
                .orElseThrow(() -> new AssertionError("accepted payment run did not persist a question plan"));
        PendingTerminalResponse response = state.pendingTerminalResponse()
                .orElseThrow(() -> new AssertionError(
                        "payment run concluded without an accepted terminal response: outcome="
                                + state.finalOutcome().map(Enum::name).orElse("NONE")
                                + "; runtimeNoticeReason="
                                + state.runtimeNoticeReason().map(Enum::name).orElse("NONE")));
        assertThat(response).isInstanceOf(PendingTerminalResponse.Answer.class);
        PendingTerminalResponse.Answer answer = (PendingTerminalResponse.Answer) response;
        assertThat(answer.acceptance().verificationBasis()).isEqualTo(AnswerVerificationBasis.LLM);
        assertThat(answer.acceptance().verdict()).hasValueSatisfying(verdict -> {
            if (expectation == ScenarioExpectation.KNOWN_SOURCE) {
                assertThat(verdict.disposition()).isIn(
                        AnswerDisposition.ACCEPTED_COMPLETE,
                        AnswerDisposition.ACCEPTED_INCONCLUSIVE);
                assertThat(state.finalOutcome()).contains(answer.acceptance().expectedOutcome());
            } else {
                assertThat(verdict.disposition()).isEqualTo(AnswerDisposition.ACCEPTED_INCONCLUSIVE);
                assertThat(state.finalOutcome()).contains(RunOutcome.INCONCLUSIVE);
            }
        });
        return new AcceptedState(plan, answer);
    }

    private CitedEvidence assertCitedEvidence(AgentRunState state, PendingTerminalResponse.Answer answer) {
        CitedEvidence citedEvidence = collectCitedEvidence(state, answer);
        assertThat(citedEvidence.handles()).isNotEmpty();
        return citedEvidence;
    }

    private CitedEvidence assertOptionalCitedEvidence(AgentRunState state, PendingTerminalResponse.Answer answer) {
        return collectCitedEvidence(state, answer);
    }

    private CitedEvidence collectCitedEvidence(AgentRunState state, PendingTerminalResponse.Answer answer) {
        Map<String, IssuedEvidence> evidenceByHandle = new LinkedHashMap<>();
        state.currentAttempt().issuedEvidence().forEach((handle, issued) -> {
            assertCurrentBinding(state, handle);
            evidenceByHandle.put(handle.value(), issued);
        });
        Set<String> citedHandleValues = new LinkedHashSet<>();
        answer.document().statements().forEach(statement ->
                statement.citations().forEach(citation -> citedHandleValues.add(citation.value())));
        List<IssuedEvidence> citedEvidence = citedHandleValues.stream()
                .map(handle -> Optional.ofNullable(evidenceByHandle.get(handle))
                        .orElseThrow(() -> new AssertionError("final answer citation was not issued in the current attempt")))
                .toList();
        citedEvidence.forEach(issued -> {
            assertCurrentBinding(state, issued.handle());
            assertThat(issued.evidence().repositoryId()).isEqualTo(REPOSITORY_ID);
            assertThat(issued.evidence().repositoryRevision()).isEqualTo(REPOSITORY_REVISION);
        });
        return new CitedEvidence(Set.copyOf(citedHandleValues), citedEvidence);
    }

    private void assertScenarioExpectation(
            ScenarioExpectation expectation,
            AgentRunState state,
            PendingTerminalResponse.Answer answer,
            List<NeedResolution> resolutions) {
        if (expectation == ScenarioExpectation.KNOWN_SOURCE) {
            return;
        }
        assertThat(answer.document().statements()).extracting(AnswerStatement::type)
                .containsAnyOf(StatementType.UNCERTAINTY, StatementType.LIMITATION);
        if (expectation == ScenarioExpectation.RUNTIME_ONLY) {
            assertThat(resolutions).allMatch(
                    resolution -> resolution.status() == NeedResolutionStatus.UNAVAILABLE);
            assertThat(answer.document().statements()).extracting(AnswerStatement::type)
                    .doesNotContain(StatementType.FACT);
        }
        if (expectation == ScenarioExpectation.ABSENT_BUSINESS) {
            assertThat(resolutions).allMatch(
                    resolution -> resolution.status() == NeedResolutionStatus.UNAVAILABLE);
            assertThat(answer.document().statements()).extracting(AnswerStatement::type)
                    .doesNotContain(StatementType.FACT);
            assertAbsentBusinessObservationAuthority(state, resolutions);
        }
        assertUnavailableObservationAuthority(state, resolutions);
    }

    private void assertUnavailableObservationAuthority(
            AgentRunState state,
            List<NeedResolution> resolutions) {
        Map<ObservationId, AgentObservation> observations = state.currentAttempt().observations();
        List<ObservationCode> usedCodes = resolutions.stream()
                .filter(resolution -> resolution.status() == NeedResolutionStatus.UNAVAILABLE)
                .flatMap(resolution -> unavailableObservationCodes(observations, resolution).stream())
                .toList();
        assertThat(usedCodes).isNotEmpty();
    }

    private void assertAbsentBusinessObservationAuthority(
            AgentRunState state,
            List<NeedResolution> resolutions) {
        Map<ObservationId, AgentObservation> observations = state.currentAttempt().observations();
        resolutions.stream()
                .filter(resolution -> resolution.status() == NeedResolutionStatus.UNAVAILABLE)
                .forEach(resolution -> assertThat(unavailableObservationCodes(observations, resolution))
                        .contains(ObservationCode.UNSUPPORTED_CLAIM));
    }

    private List<ObservationCode> unavailableObservationCodes(
            Map<ObservationId, AgentObservation> observations,
            NeedResolution resolution) {
        return resolution.observations().stream()
                .map(observationId -> Optional.ofNullable(observations.get(observationId))
                        .orElseThrow(() -> new AssertionError(
                                "unavailable resolution did not use a current-attempt observation")))
                .map(AgentObservation::code)
                .toList();
    }

    private List<NeedResolution> assertPlanAndResolutionAuthority(
            AgentRunState state,
            QuestionPlan plan,
            PendingTerminalResponse.Answer answer,
            CitedEvidence citedEvidence) {
        List<ModelInteraction> interactions = state.modelInteractions();
        List<ActionResult.QuestionPlanRecorded> recordedPlans = interactions.stream()
                .filter(ModelInteraction.ActionResultRecorded.class::isInstance)
                .map(ModelInteraction.ActionResultRecorded.class::cast)
                .map(ModelInteraction.ActionResultRecorded::result)
                .filter(ActionResult.QuestionPlanRecorded.class::isInstance)
                .map(ActionResult.QuestionPlanRecorded.class::cast)
                .toList();
        assertThat(recordedPlans).containsExactly(new ActionResult.QuestionPlanRecorded(plan));

        List<Integer> planIndexes = IntStream.range(0, interactions.size())
                .filter(index -> interactions.get(index) instanceof ModelInteraction.ActionResultRecorded recorded
                        && recorded.result() instanceof ActionResult.QuestionPlanRecorded)
                .boxed()
                .toList();
        assertThat(planIndexes).singleElement();
        int planIndex = planIndexes.getFirst();

        List<Integer> queryIndexes = IntStream.range(0, interactions.size())
                .filter(index -> interactions.get(index) instanceof ModelInteraction.ActionSelected selected
                        && selected.action() instanceof QueryAction)
                .boxed()
                .toList();
        assertThat(queryIndexes).isNotEmpty();
        assertThat(queryIndexes).allSatisfy(index -> assertThat(index).isGreaterThan(planIndex));

        List<Integer> acceptedAnswerIndexes = IntStream.range(0, interactions.size() - 1)
                .filter(index -> interactions.get(index) instanceof ModelInteraction.ActionSelected selected
                        && selected.action() instanceof AnswerAction action
                        && action.document().equals(answer.document())
                        && interactions.get(index + 1) instanceof ModelInteraction.ActionResultRecorded recorded
                        && recorded.result() instanceof ActionResult.AnswerAccepted)
                .boxed()
                .toList();
        assertThat(acceptedAnswerIndexes).singleElement();
        int acceptedAnswerIndex = acceptedAnswerIndexes.getFirst();
        assertThat(acceptedAnswerIndex).isGreaterThan(planIndex);
        AnswerAction acceptedAction = (AnswerAction) ((ModelInteraction.ActionSelected) interactions
                .get(acceptedAnswerIndex)).action();
        assertThat(plan.needs()).extracting(need -> need.id().value())
                .containsExactlyElementsOf(acceptedAction.resolutions().stream()
                        .map(NeedResolution::needId)
                        .map(needId -> needId.value())
                        .toList());

        Map<String, IssuedEvidence> issuedEvidence = new LinkedHashMap<>();
        state.currentAttempt().issuedEvidence().forEach((handle, issued) -> issuedEvidence.put(handle.value(), issued));
        acceptedAction.resolutions().forEach(resolution -> {
            if (resolution.status() == NeedResolutionStatus.SUPPORTED) {
                resolution.evidence().forEach(reference -> {
                    IssuedEvidence issued = Optional.ofNullable(issuedEvidence.get(reference.value()))
                            .orElseThrow(() -> new AssertionError("supported resolution did not use current issued evidence"));
                    assertCurrentBinding(state, issued.handle());
                    assertThat(citedEvidence.handles()).contains(reference.value());
                });
            }
            if (resolution.status() == NeedResolutionStatus.UNAVAILABLE) {
                resolution.observations().forEach(observationId ->
                        assertThat(state.currentAttempt().observations()).containsKey(observationId));
            }
        });
        return acceptedAction.resolutions();
    }

    private void assertCitedCapabilityProvenance(AgentRunState state, CitedEvidence citedEvidence) {
        List<EvidenceCapabilityProvenance> provenance = EvidenceCapabilityProvenance.resolve(
                state.currentAttempt().issuedCapabilities(),
                state.currentAttempt().issuedEvidence(),
                state.modelInteractions()).stream()
                .filter(item -> citedEvidence.handles().contains(item.evidenceHandle().value()))
                .toList();
        assertThat(provenance).extracting(item -> item.evidenceHandle().value())
                .containsExactlyInAnyOrderElementsOf(citedEvidence.handles());
        provenance.forEach(item -> {
            assertCurrentBinding(state, item.evidenceHandle());
            CapabilityPolicy capability = item.capability();
            assertThat(capability.name()).startsWith("codebase_");
            assertThat(capability.version()).isNotBlank();
        });
    }

    private void assertCurrentBinding(AgentRunState state, EvidenceHandle handle) {
        assertThat(handle.binding().runId()).isEqualTo(state.runId());
        assertThat(handle.binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
        assertThat(handle.binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
    }

    private void assertNoEligibleInboxBeforeSubmission() {
        assertThat(nextEligibleInboxRunId(Instant.now()))
                .as("dedicated live database must not contain a foreign eligible inbox row")
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
                      SELECT 1 FROM session_inbox processing WHERE processing.status = 'PROCESSING'
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

    private NormalizedSourceEvent sourceEvent(PaymentScenario scenario, String identity, Instant receivedAt) {
        return new NormalizedSourceEvent(
                SOURCE_TYPE,
                new TransportEventId(identity),
                new SourceMessageId(identity),
                new SessionSourceRef(SOURCE_TYPE, identity),
                new ParticipantRef(SOURCE_TYPE, identity + "-participant"),
                scenario.question(),
                scenario.question(),
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        SOURCE_TYPE,
                        identity + "-participant",
                        identity,
                        identity,
                        identity,
                        scenario.question()),
                receivedAt);
    }

    private void pauseForDurablePoll() throws InterruptedException {
        Thread.sleep(POLL_DELAY);
    }

    private SourceShas requiredSourceShas() {
        return new SourceShas(
                requiredSourceSha("KNOWLEDGE_AGENT_SOURCE_SHA"),
                requiredSourceSha("KNOWLEDGE_SEMANTIC_SOURCE_SHA"),
                requiredSourceSha("KNOWLEDGE_STARTER_SOURCE_SHA"));
    }

    private String requiredSourceSha(String name) {
        String sourceSha = Optional.ofNullable(System.getenv(name))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("required environment variable is not set: " + name));
        if (!SOURCE_SHA.matcher(sourceSha).matches()) {
            throw new IllegalStateException("required environment variable must be a lowercase 40-character source SHA: " + name);
        }
        return sourceSha;
    }

    private Optional<Path> reportDirectoryIfRequested() {
        return Optional.ofNullable(System.getenv("KNOWLEDGE_REPORT_DIRECTORY"))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .map(reportDirectory -> {
                    if (!Files.isDirectory(reportDirectory) || !Files.isWritable(reportDirectory)) {
                        throw new IllegalStateException(
                                "KNOWLEDGE_REPORT_DIRECTORY must be an existing writable directory");
                    }
                    return reportDirectory;
                });
    }

    private void writeReportIfRequested(
            Optional<Path> reportDirectory,
            PaymentScenario scenario,
            SourceAdmission admission,
            TerminalProcessing terminal,
            AcceptedRun acceptedRun,
            CitedEvidence citedEvidence,
            SourceShas sourceShas) throws IOException {
        if (reportDirectory.isEmpty()) {
            return;
        }
        AgentRunState state = terminal.state();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", scenario.id());
        report.put("fixtureId", FIXTURE_ID);
        report.put("fixtureRevision", REPOSITORY_REVISION.value());
        report.put("sessionId", admission.sessionId().value());
        report.put("runId", admission.runId().value());
        report.put("planNeedIds", acceptedRun.plan().needs().stream().map(need -> need.id().value()).toList());
        report.put("planNeedCount", acceptedRun.plan().needs().size());
        report.put("resolutionStatuses", acceptedRun.resolutions().stream()
                .map(resolution -> resolution.status().name())
                .toList());
        report.put("terminalOutcome", state.finalOutcome().orElseThrow().name());
        report.put("verifierDisposition", acceptedRun.answer().acceptance().verdict()
                .orElseThrow()
                .disposition()
                .name());
        report.put("unavailableObservationCodes", acceptedRun.resolutions().stream()
                .filter(resolution -> resolution.status() == NeedResolutionStatus.UNAVAILABLE)
                .flatMap(resolution -> resolution.observations().stream())
                .map(observationId -> state.currentAttempt().observations().get(observationId))
                .filter(Objects::nonNull)
                .map(AgentObservation::code)
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList());
        List<Map<String, String>> citedProvenance = citedEvidence.evidence().stream()
                .map(issued -> Map.of(
                        "handle", issued.handle().value(),
                        "capability", citedCapability(state, issued.handle()),
                        "source", issued.evidence().sourceService(),
                        "repository", issued.evidence().repositoryId().value(),
                        "revision", issued.evidence().repositoryRevision().value()))
                .toList();
        report.put("citedCapabilities", citedProvenance.stream()
                .map(entry -> entry.get("capability"))
                .distinct()
                .sorted()
                .toList());
        report.put("citedEvidenceProvenance", citedProvenance);
        report.put("modelId", Optional.ofNullable(environment.getProperty("spring.ai.google.genai.chat.model"))
                .orElse("unknown"));
        report.put("promptCatalogDigest", promptResourceCatalog.catalogDigest());
        report.put("capacityDeferrals", terminal.capacityDeferrals());
        report.put("KNOWLEDGE_AGENT_SOURCE_SHA", sourceShas.agent());
        report.put("KNOWLEDGE_SEMANTIC_SOURCE_SHA", sourceShas.semantic());
        report.put("KNOWLEDGE_STARTER_SOURCE_SHA", sourceShas.starter());
        Path reportPath = reportDirectory.orElseThrow().resolve(
                "payment-knowledge-" + admission.runId().value() + ".json");
        Files.writeString(reportPath, objectMapper.writeValueAsString(report), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private String citedCapability(AgentRunState state, EvidenceHandle handle) {
        return EvidenceCapabilityProvenance.resolve(
                        state.currentAttempt().issuedCapabilities(),
                        state.currentAttempt().issuedEvidence(),
                        state.modelInteractions())
                .stream()
                .filter(item -> item.evidenceHandle().equals(handle))
                .map(EvidenceCapabilityProvenance::capability)
                .map(capability -> capability.name() + "@" + capability.version())
                .findFirst()
                .orElseThrow(() -> new AssertionError("cited evidence did not resolve to issued capability provenance"));
    }

    private record AcceptedState(QuestionPlan plan, PendingTerminalResponse.Answer answer) {
    }

    private record AcceptedRun(
            QuestionPlan plan,
            PendingTerminalResponse.Answer answer,
            List<NeedResolution> resolutions) {
    }

    private record TerminalProcessing(AgentRunState state, int capacityDeferrals) {
    }

    private record PaymentScenario(String id, String question, ScenarioExpectation expectation) {
    }

    private record ScenarioRun(
            PaymentScenario scenario,
            SourceAdmission admission,
            TerminalProcessing terminal) {
    }

    private enum ScenarioExpectation {
        KNOWN_SOURCE,
        RUNTIME_ONLY,
        ABSENT_BUSINESS
    }

    private record CitedEvidence(Set<String> handles, List<IssuedEvidence> evidence) {
    }

    private record SourceShas(String agent, String semantic, String starter) {
    }
}
