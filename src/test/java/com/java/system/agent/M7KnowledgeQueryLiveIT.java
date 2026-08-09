package com.java.system.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以真實 runtime 與外部 live infrastructure 驗證 M7 repository 知識查詢
 */
@SpringBootTest(properties = "spring.profiles.include=m7-knowledge-live")
@ActiveProfiles("agent-runtime")
@Import(M7KnowledgeQueryLiveIT.DeliveryTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "M7_KNOWLEDGE_LIVE", matches = "true")
class M7KnowledgeQueryLiveIT {

    private static final String QUESTION = "在 m7-knowledge-query repository 中，訂單處理有哪些 HTTP API、排程與訊息消費入口？請列出各入口的 package、class、method，說明它們如何進入共同的訂單處理流程，並提供完整方法來源與可驗證的呼叫、implementation、internal-reference evidence。";
    private static final RepositoryId REPOSITORY_ID = new RepositoryId("m7-knowledge-query");
    private static final RepositoryRevision REPOSITORY_REVISION = new RepositoryRevision("FIXTURE");
    private static final RevisionVector EXPECTED_REVISIONS = RevisionVector.fromEntries(
            List.of(new RevisionVector.Entry(REPOSITORY_ID, REPOSITORY_REVISION)));

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

    @Test
    void should_complete_m7_knowledge_query_through_the_normal_runtime() throws IOException {
        Instant processingTime = Instant.now().plus(Duration.ofMinutes(1));
        String externalRunId = UUID.randomUUID().toString();
        SourceAcceptance acceptance = sourceAcceptance.accept(sourceEvent(externalRunId, processingTime.minusSeconds(1)));
        assertThat(acceptance.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
        assertThat(acceptance.admission()).isPresent();
        SourceAdmission admission = acceptance.admission().orElseThrow();

        InboxProcessingOutcome inboxOutcome = inboxProcessor.processNext(processingTime)
                .orElseThrow(() -> new AssertionError("normal source admission did not produce an inbox claim"));
        assertThat(inboxOutcome).isEqualTo(InboxProcessingOutcome.COMPLETED);

        AgentRunState state = transitionPort.findByRunId(admission.runId())
                .orElseThrow(() -> new AssertionError("normal source admission did not persist a run state"));
        PendingTerminalResponse.Answer acceptedAnswer = assertCompletedAcceptedState(state, admission);
        Set<String> citedHandleValues = assertCitationsAndEvidence(state, acceptedAnswer);

        DeliveryProcessingOutcome receiptOutcome = deliveryProcessor.processNext(processingTime)
                .orElseThrow(() -> new AssertionError("receipt delivery was not claimable"));
        DeliveryProcessingOutcome finalOutcome = deliveryProcessor.processNext(processingTime.plusSeconds(1))
                .orElseThrow(() -> new AssertionError("final delivery was not claimable after its receipt"));
        assertThat(receiptOutcome).isEqualTo(DeliveryProcessingOutcome.DELIVERED);
        assertThat(finalOutcome).isEqualTo(DeliveryProcessingOutcome.DELIVERED);
        assertDeliveryRendering(acceptedAnswer);

        writeManifestIfRequested(externalRunId, state, citedHandleValues);
    }

    private PendingTerminalResponse.Answer assertCompletedAcceptedState(AgentRunState state, SourceAdmission admission) {
        assertThat(state.runId()).isEqualTo(admission.runId());
        assertThat(state.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(state.finalOutcome()).contains(RunOutcome.COMPLETED);
        assertThat(state.currentAttempt().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
        assertThat(state.budget().maxAgentSteps()).isEqualTo(6);
        assertThat(state.budget().maxQueryExecutions()).isEqualTo(5);
        assertThat(state.budget().usedQueryExecutions()).isLessThanOrEqualTo(5);
        assertThat(state.currentAttempt().issuedCandidates()).isNotEmpty();
        assertThat(state.currentAttempt().issuedEvidence()).isNotEmpty();
        state.currentAttempt().issuedCandidates().forEach((handle, issued) -> {
            assertThat(handle.binding().runId()).isEqualTo(state.runId());
            assertThat(handle.binding().attemptId()).isEqualTo(state.currentAttempt().attemptId());
            assertThat(handle.binding().revisionVector()).isEqualTo(EXPECTED_REVISIONS);
            assertThat(issued.candidate().repositoryId()).isEqualTo(REPOSITORY_ID);
            issued.candidate().repositoryRevision().ifPresent(revision ->
                    assertThat(revision).isEqualTo(REPOSITORY_REVISION));
        });
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

    private Set<String> assertCitationsAndEvidence(
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
            assertThat(issued.evidence().warnings()).allSatisfy(warning ->
                    assertThat(warning.code().toUpperCase(Locale.ROOT))
                            .doesNotContain("PARTIAL", "TRUNCATED", "UNRESOLVED", "REVISION_MISMATCH"));
        });

        String citedEvidenceContent = citedEvidence.stream()
                .map(issued -> issued.evidence().content())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(citedEvidenceContent).contains(
                "OrderController", "submitOrder",
                "OrderRecoveryJob", "retryPendingOrders",
                "OrderMessageListener", "onOrderRequested",
                "OrderWorkflow", "processOrder",
                "DefaultOrderWorkflow", "implementation",
                "edge=", "internalReference",
                "repository.save");
        long callerRelationshipCount = citedEvidence.stream()
                .map(issued -> issued.evidence().content())
                .mapToLong(content -> content.split("edge=", -1).length - 1L)
                .sum();
        assertThat(callerRelationshipCount).isGreaterThanOrEqualTo(3);
        assertThat(citedEvidence.stream()
                .map(issued -> issued.evidence().content())
                .filter(content -> content.contains("public void processOrder"))
                .toList()).isNotEmpty();
        return Set.copyOf(citedHandleValues);
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

    private void writeManifestIfRequested(String externalRunId, AgentRunState state, Set<String> citedHandleValues)
            throws IOException {
        Optional<Path> reportDirectory = Optional.ofNullable(System.getenv("M7_REPORT_DIRECTORY"))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .filter(Files::isDirectory);
        if (reportDirectory.isEmpty()) {
            return;
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("externalRunId", externalRunId);
        manifest.put("terminalOutcome", state.finalOutcome().orElseThrow().name());
        manifest.put("verdict", AnswerDisposition.ACCEPTED_COMPLETE.name());
        manifest.put("repository", REPOSITORY_ID.value());
        manifest.put("revision", REPOSITORY_REVISION.value());
        manifest.put("modelName", Optional.ofNullable(environment.getProperty("spring.ai.google.genai.chat.model"))
                .orElse("unknown"));
        manifest.put("budgetUsage", Map.of(
                "agentSteps", state.budget().usedAgentSteps(),
                "queryExecutions", state.budget().usedQueryExecutions()));
        manifest.put("observationHandles", state.currentAttempt().observations().keySet().stream()
                .map(observation -> observation.value())
                .toList());
        manifest.put("issuedEvidenceHandles", state.currentAttempt().issuedEvidence().keySet().stream()
                .map(EvidenceHandle::value)
                .toList());
        manifest.put("citedHandles", citedHandleValues.stream().sorted().toList());
        manifest.put("deliveryCounts", Map.of(
                "receipt", countDeliveries(DeliveryKind.RECEIPT),
                "finalResponse", countDeliveries(DeliveryKind.FINAL_RESPONSE)));
        Path manifestPath = reportDirectory.orElseThrow()
                .resolve("m7-knowledge-query-manifest-" + externalRunId + ".json");
        Files.writeString(manifestPath, objectMapper.writeValueAsString(manifest), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private long countDeliveries(DeliveryKind kind) {
        return recordingDeliveryTransport.deliveries().stream()
                .filter(delivery -> delivery.kind().equals(kind))
                .count();
    }

    private NormalizedSourceEvent sourceEvent(String externalRunId, Instant receivedAt) {
        return new NormalizedSourceEvent(
                "live-test",
                new TransportEventId(externalRunId),
                new SourceMessageId(externalRunId),
                new SessionSourceRef("live-test", externalRunId),
                new com.java.system.agent.answering.domain.conversation.ParticipantRef("live-test", "m7-knowledge-query"),
                QUESTION,
                QUESTION,
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        "live-test", "m7-knowledge-query", externalRunId, externalRunId,
                        "m7-knowledge-query", QUESTION),
                receivedAt);
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
