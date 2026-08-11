package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("agent-runtime")
@EnabledIfEnvironmentVariable(named = "PAYMENT_KNOWLEDGE_LIVE", matches = "true")
class PaymentKnowledgeLiveIT {

    private static final String SOURCE_TYPE = "payment-knowledge-live";
    private static final String FIXTURE_ID = "payment-knowledge-query";
    private static final String SCENARIO_ID = "payment-options";
    private static final String QUESTION =
            "我們目前支援哪些付款方式？各自會收手續費嗎？如果費用會依條件不同，請一併說明。";
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
    private Environment environment;

    @Autowired
    private PromptResourceCatalog promptResourceCatalog;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void answers_payment_knowledge_through_the_normal_durable_runtime() throws IOException, InterruptedException {
        SourceShas sourceShas = requiredSourceShas();
        assertNoEligibleInboxBeforeSubmission();

        String identity = "payment-knowledge-" + UUID.randomUUID();
        SourceAcceptance acceptance = sourceAcceptance.accept(sourceEvent(identity, Instant.now()));
        assertThat(acceptance.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
        assertThat(acceptance.admission()).isPresent();
        SourceAdmission admission = acceptance.admission().orElseThrow();

        TerminalProcessing terminal = processUntilTerminal(admission, Instant.now().plus(TERMINAL_TIMEOUT));
        AcceptedState acceptedState = assertAcceptedRun(admission, terminal.state());
        CitedEvidence citedEvidence = assertCitedEvidence(terminal.state(), acceptedState.answer());
        List<NeedResolution> resolutions = assertPlanAndResolutionAuthority(
                terminal.state(), acceptedState.plan(), acceptedState.answer(), citedEvidence);
        AcceptedRun acceptedRun = new AcceptedRun(acceptedState.plan(), acceptedState.answer(), resolutions);
        assertCitedCapabilityProvenance(terminal.state(), citedEvidence);
        writeReportIfRequested(admission, terminal, acceptedRun, citedEvidence, sourceShas);
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

    private AcceptedState assertAcceptedRun(SourceAdmission admission, AgentRunState state) {
        assertThat(state.runId()).isEqualTo(admission.runId());
        assertThat(state.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(state.currentAttempt().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
        assertThat(state.finalOutcome()).hasValueSatisfying(outcome ->
                assertThat(outcome).isIn(RunOutcome.COMPLETED, RunOutcome.INCONCLUSIVE));
        QuestionPlan plan = state.questionPlan()
                .orElseThrow(() -> new AssertionError("accepted payment run did not persist a question plan"));
        PendingTerminalResponse response = state.pendingTerminalResponse()
                .orElseThrow(() -> new AssertionError("accepted payment run did not retain a terminal response"));
        assertThat(response).isInstanceOf(PendingTerminalResponse.Answer.class);
        PendingTerminalResponse.Answer answer = (PendingTerminalResponse.Answer) response;
        assertThat(answer.acceptance().verificationBasis()).isEqualTo(AnswerVerificationBasis.LLM);
        assertThat(answer.acceptance().verdict()).hasValueSatisfying(verdict -> {
            assertThat(verdict.disposition()).isIn(
                    AnswerDisposition.ACCEPTED_COMPLETE,
                    AnswerDisposition.ACCEPTED_INCONCLUSIVE);
            assertThat(state.finalOutcome()).contains(answer.acceptance().expectedOutcome());
        });
        return new AcceptedState(plan, answer);
    }

    private CitedEvidence assertCitedEvidence(AgentRunState state, PendingTerminalResponse.Answer answer) {
        Map<String, IssuedEvidence> evidenceByHandle = new LinkedHashMap<>();
        state.currentAttempt().issuedEvidence().forEach((handle, issued) -> {
            assertCurrentBinding(state, handle);
            evidenceByHandle.put(handle.value(), issued);
        });
        Set<String> citedHandleValues = new LinkedHashSet<>();
        answer.document().statements().forEach(statement ->
                statement.citations().forEach(citation -> citedHandleValues.add(citation.value())));
        assertThat(citedHandleValues).isNotEmpty();
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

    private NormalizedSourceEvent sourceEvent(String identity, Instant receivedAt) {
        return new NormalizedSourceEvent(
                SOURCE_TYPE,
                new TransportEventId(identity),
                new SourceMessageId(identity),
                new SessionSourceRef(SOURCE_TYPE, identity),
                new ParticipantRef(SOURCE_TYPE, identity + "-participant"),
                QUESTION,
                QUESTION,
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        SOURCE_TYPE, identity + "-participant", identity, identity, identity, QUESTION),
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

    private void writeReportIfRequested(
            SourceAdmission admission,
            TerminalProcessing terminal,
            AcceptedRun acceptedRun,
            CitedEvidence citedEvidence,
            SourceShas sourceShas) throws IOException {
        Optional<Path> reportDirectory = Optional.ofNullable(System.getenv("KNOWLEDGE_REPORT_DIRECTORY"))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .filter(Files::isDirectory);
        if (reportDirectory.isEmpty()) {
            return;
        }
        AgentRunState state = terminal.state();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", SCENARIO_ID);
        report.put("fixtureId", FIXTURE_ID);
        report.put("fixtureRevision", REPOSITORY_REVISION.value());
        report.put("sessionId", admission.sessionId().value());
        report.put("runId", admission.runId().value());
        report.put("planNeedIds", acceptedRun.plan().needs().stream().map(need -> need.id().value()).toList());
        report.put("planNeedCount", acceptedRun.plan().needs().size());
        report.put("resolutionStatuses", acceptedRun.resolutions().stream()
                .map(resolution -> resolution.status().name())
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

    private record CitedEvidence(Set<String> handles, List<IssuedEvidence> evidence) {
    }

    private record SourceShas(String agent, String semantic, String starter) {
    }
}
