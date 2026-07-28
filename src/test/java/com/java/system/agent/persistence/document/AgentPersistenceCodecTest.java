package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerAcceptance;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.domain.capability.ArgumentDefinition;
import com.java.system.agent.runtime.domain.capability.ArgumentType;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.candidate.RouteCandidate;
import com.java.system.agent.runtime.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceWarning;
import com.java.system.agent.runtime.domain.evidence.IssuedEvidence;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.evidence.SourceRange;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.handle.CapabilityHandle;
import com.java.system.agent.runtime.domain.handle.EvidenceHandle;
import com.java.system.agent.runtime.domain.handle.HandleBinding;
import com.java.system.agent.runtime.domain.observation.AgentObservation;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationId;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentRunStatus;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.PendingTerminalResponse;
import com.java.system.agent.runtime.domain.run.PendingAnswerVerification;
import com.java.system.agent.runtime.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.runtime.domain.run.RunAttempt;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunRequestIdentity;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 狀態與事件持久化文件的相容性邊界測試
 */
class AgentPersistenceCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentStateDocumentCodec stateCodec = new AgentStateDocumentCodec(objectMapper);
    private final AgentEventDocumentCodec eventCodec = new AgentEventDocumentCodec(objectMapper);

    @Test
    void should_round_trip_initial_active_and_terminal_states_without_losing_order_or_subtypes() {
        List<AgentRunState> states = List.of(initialState(), activeState(), pendingVerificationState(),
                concludedAnswerState(), concludedClarificationState());

        for (AgentRunState state : states) {
            VersionedJsonDocument document = stateCodec.encode(state);

            assertThat(document.schemaVersion()).isEqualTo(4);
            assertThat(document.payload().path("request_identity").has("question_text")).isTrue();
            assertThat(document.payload().path("request_identity").path("participant_source_type").asText())
                    .isEqualTo("test");
            assertThat(document.payload().path("request_identity").path("participant_key").asText())
                    .isEqualTo("participant-1");
            assertThat(document.payload().path("request_identity").has("exact_question")).isFalse();
            assertThat(stateCodec.decode(document)).isEqualTo(state);
        }
    }

    @ParameterizedTest
    @MethodSource("events")
    void should_round_trip_every_event_variant_with_its_stable_discriminator(AgentEvent event) {
        VersionedJsonDocument document = eventCodec.encode(event);

        assertThat(document.schemaVersion()).isEqualTo(3);
        assertThat(document.payload().path("event_type").asText()).isNotBlank();
        assertThat(eventCodec.decode(document.payload().path("event_type").asText(), document)).isEqualTo(event);
    }

    @Test
    void should_fail_closed_for_unknown_versions_discriminators_and_mismatched_event_type() {
        VersionedJsonDocument stateDocument = stateCodec.encode(initialState());
        JsonNode unknownState = objectMapper.createObjectNode().put("state_type", "UNKNOWN");
        VersionedJsonDocument eventDocument = eventCodec.encode(new AgentEvent.RunStarted(runId(), attemptId(), 0));
        ObjectNode unknownEvent = eventDocument.payload().deepCopy();
        unknownEvent.put("event_type", "UNKNOWN");

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(5, stateDocument.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document schema version");
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(3, stateDocument.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document schema version");
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(1, stateDocument.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document schema version");
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(2, stateDocument.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document schema version");
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, unknownState)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document discriminator");
        assertThatThrownBy(() -> eventCodec.decode(
                eventCodec.eventType(new AgentEvent.RunStarted(runId(), attemptId(), 0)),
                new VersionedJsonDocument(4, eventDocument.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported event document schema version");
        assertThatThrownBy(() -> eventCodec.decode(
                eventCodec.eventType(new AgentEvent.RunStarted(runId(), attemptId(), 0)),
                new VersionedJsonDocument(1, eventDocument.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported event document schema version");
        assertThatThrownBy(() -> eventCodec.decode(
                eventCodec.eventType(new AgentEvent.RunStarted(runId(), attemptId(), 0)),
                new VersionedJsonDocument(2, eventDocument.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported event document schema version");
        assertThatThrownBy(() -> eventCodec.decode("UNKNOWN", new VersionedJsonDocument(3, unknownEvent)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported event document discriminator");
        assertThatThrownBy(() -> eventCodec.decode("ACTION_ACCEPTED", eventDocument))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("relational event type does not match event payload");
    }

    @Test
    void writes_only_current_capability_budget_and_observation_fields() {
        ObjectNode state = (ObjectNode) stateCodec.encode(activeState()).payload();
        ObjectNode budget = (ObjectNode) state.path("budget");
        ObjectNode observation = (ObjectNode) state.path("current_attempt").path("observations").path(0)
                .path("observation");

        assertThat(budget.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "max_agent_steps", "used_agent_steps", "max_query_executions", "used_query_executions",
                "max_action_rejections", "used_action_rejections", "max_revision_restarts",
                "used_revision_restarts", "final_answer_reserve", "used_final_answers");
        assertThat(budget.has("max_query_executions")).isTrue();
        assertThat(budget.has("used_query_executions")).isTrue();
        assertThat(budget.has("max_semantic_queries")).isFalse();
        assertThat(observation.path("source").asText()).isEqualTo("CAPABILITY_EXECUTOR");
        assertThat(observation.has("semantic_source")).isFalse();
        assertThat(state.toString()).contains("semantic_target");
    }

    @Test
    void should_fail_closed_for_unknown_top_level_and_nested_document_fields() {
        VersionedJsonDocument stateDocument = stateCodec.encode(activeState());
        VersionedJsonDocument clarificationDocument = stateCodec.encode(concludedClarificationState());
        ObjectNode unknownTopLevel = stateDocument.payload().deepCopy();
        ObjectNode unknownNested = stateDocument.payload().deepCopy();
        ObjectNode unknownNestedAction = clarificationDocument.payload().deepCopy();
        unknownTopLevel.put("unexpected", "private state content");
        ((ObjectNode) unknownNested.path("current_attempt")).put("unexpected", "private nested content");
        ((ObjectNode) unknownNestedAction.path("pending_terminal_response").path("action"))
                .put("unexpected", "private action content");

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, unknownTopLevel)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported document field")
                .hasNoCause();
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, unknownNested)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported document field")
                .hasNoCause();
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, unknownNestedAction)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported document field")
                .hasNoCause();
    }

    @Test
    void should_reject_the_pre_m3_request_identity_field_without_a_compatibility_fallback() {
        ObjectNode legacyRequestIdentity = stateCodec.encode(initialState()).payload().deepCopy();
        ObjectNode identity = (ObjectNode) legacyRequestIdentity.path("request_identity");
        String questionText = identity.remove("question_text").asText();
        identity.put("exact_question", questionText);

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, legacyRequestIdentity)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported document field")
                .hasNoCause();
    }

    @Test
    void should_reject_a_request_identity_without_the_persisted_participant() {
        ObjectNode legacyRequestIdentity = stateCodec.encode(initialState()).payload().deepCopy();
        ObjectNode identity = (ObjectNode) legacyRequestIdentity.path("request_identity");
        identity.remove("participant_source_type");
        identity.remove("participant_key");

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, legacyRequestIdentity)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasNoCause();
    }

    @Test
    void should_fail_closed_when_a_nullable_state_field_is_missing() {
        VersionedJsonDocument document = stateCodec.encode(concludedClarificationState());
        ObjectNode missingPendingResponse = document.payload().deepCopy();
        missingPendingResponse.remove("pending_terminal_response");

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, missingPendingResponse)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("missing nullable document field")
                .hasNoCause();
    }

    @Test
    void should_fail_closed_for_duplicate_entries_in_persisted_sets() {
        ObjectNode capabilityKinds = stateCodec.encode(activeState()).payload().deepCopy();
        ArrayNode kinds = (ArrayNode) capabilityKinds.path("current_attempt")
                .path("issued_capabilities").path(0).path("descriptor").path("accepted_candidate_kinds");
        kinds.add(kinds.get(0).deepCopy());

        ObjectNode enumValues = stateCodec.encode(activeState()).payload().deepCopy();
        ArrayNode values = (ArrayNode) enumValues.path("current_attempt")
                .path("issued_capabilities").path(0).path("descriptor")
                .path("query_schema").path("arguments").path(3).path("enum_values");
        values.add(values.get(0).deepCopy());

        ObjectNode observationHandles = stateCodec.encode(activeState()).payload().deepCopy();
        ArrayNode handles = (ArrayNode) observationHandles.path("current_attempt")
                .path("observations").path(0).path("observation").path("candidate_handles");
        handles.add(handles.get(0).deepCopy());

        ObjectNode answerCitations = stateCodec.encode(concludedAnswerState()).payload().deepCopy();
        ArrayNode citations = (ArrayNode) answerCitations.path("pending_terminal_response")
                .path("document").path("statements").path(0).path("citations");
        citations.add(citations.get(0).deepCopy());

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, capabilityKinds)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("duplicate persisted set entry");
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, enumValues)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("duplicate persisted set entry");
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, observationHandles)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("duplicate persisted set entry");
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, answerCitations)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("duplicate persisted set entry");
    }

    @Test
    void should_reject_unknown_event_fields_while_preserving_dynamic_query_arguments() {
        RunAttempt attempt = richAttempt();
        CapabilityHandle capability = attempt.issuedCapabilities().keySet().iterator().next();
        QueryAction action = new QueryAction(
                capability,
                List.copyOf(attempt.issuedCandidates().keySet()),
                "Which call handles it?",
                Map.of("plugin_specific_filter", "order"),
                "trace the call graph");
        AgentEvent.ActionAccepted event = new AgentEvent.ActionAccepted(runId(), attemptId(), 3, action);
        VersionedJsonDocument document = eventCodec.encode(event);

        assertThat(eventCodec.decode("ACTION_ACCEPTED", document)).isEqualTo(event);

        ObjectNode unknownEventField = document.payload().deepCopy();
        ObjectNode unknownFixedField = document.payload().deepCopy();
        unknownEventField.put("unexpected", "private event content");
        ((ObjectNode) unknownFixedField.path("action")).put("unexpected", "private action content");

        assertThatThrownBy(() -> eventCodec.decode(
                "ACTION_ACCEPTED",
                new VersionedJsonDocument(3, unknownEventField)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported document field")
                .hasNoCause();
        assertThatThrownBy(() -> eventCodec.decode(
                "ACTION_ACCEPTED",
                new VersionedJsonDocument(3, unknownFixedField)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported document field")
                .hasNoCause();
    }

    @Test
    void should_fail_closed_with_bounded_messages_for_malformed_state_and_event_shapes() {
        JsonNode malformedState = objectMapper.createArrayNode().add("private state content");
        JsonNode malformedEvent = objectMapper.createObjectNode()
                .put("event_type", "RUN_STARTED")
                .put("private_content", "private event content");

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(4, malformedState)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("invalid document object shape")
                .hasNoCause();
        assertThatThrownBy(() -> eventCodec.decode(
                "RUN_STARTED",
                new VersionedJsonDocument(3, malformedEvent)))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("missing required document field")
                .hasNoCause();
    }

    @Test
    void should_parse_database_json_text_and_hide_malformed_content() {
        VersionedJsonDocument stateDocument = stateCodec.encode(initialState());
        AgentEvent event = new AgentEvent.RunStarted(runId(), attemptId(), 0);
        VersionedJsonDocument eventDocument = eventCodec.encode(event);

        assertThat(stateCodec.decode(4, stateDocument.payload().toString())).isEqualTo(initialState());
        assertThat(eventCodec.decode("RUN_STARTED", 3, eventDocument.payload().toString())).isEqualTo(event);
        assertThatThrownBy(() -> stateCodec.decode(4, "{\"private\":\"state\""))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("invalid state document JSON")
                .hasNoCause();
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 3, "{\"private\":\"event\""))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("invalid event document JSON")
                .hasNoCause();
    }

    private static Stream<AgentEvent> events() {
        AgentRunState active = activeState();
        RunAttempt attempt = active.currentAttempt();
        ConversationTurn answerTurn = new ConversationTurn(runId(), participant(), "question", answerDocument().renderParagraphs(),
                ConversationTurnType.ANSWER);
        ConversationTurn clarificationTurn = new ConversationTurn(runId(), participant(), "question", "Which route applies?",
                ConversationTurnType.CLARIFICATION);
        return Stream.of(
                new AgentEvent.RunStarted(runId(), attemptId(), 0),
                new AgentEvent.AttemptStarted(runId(), attemptId(), 1, attempt),
                contextIssuedWithDistinctMapAndValueHandles(attempt),
                new AgentEvent.ActionAccepted(runId(), attemptId(), 3, queryAction()),
                new AgentEvent.ActionRejected(runId(), attemptId(), 4, Optional.of(new AnswerAction(answerDocument())),
                        "verification rejected it", true),
                new AgentEvent.QueryBudgetConsumed(runId(), attemptId(), 5),
                new AgentEvent.ObservationRecorded(runId(), attemptId(), 6, observation()),
                new AgentEvent.AttemptInvalidated(runId(), attemptId(), 7, "repository revision changed", true),
                new AgentEvent.AnswerAccepted(runId(), attemptId(), 8, answerDocument(), AnswerAcceptance.llm(acceptedVerdict()),
                        new SessionId("session-1"), answerTurn, true),
                new AgentEvent.AnswerAccepted(runId(), attemptId(), 8, answerDocument(), AnswerAcceptance.contractOnly(),
                        new SessionId("session-1"), answerTurn, false),
                new AgentEvent.AnswerProposed(runId(), attemptId(), 8, pendingVerification()),
                new AgentEvent.AnswerRejected(runId(), attemptId(), 8, rejectedVerdict()),
                new AgentEvent.AnswerVerificationAbandoned(runId(), attemptId(), 8,
                        AnswerVerificationAbandonReason.RETRY_EXHAUSTED),
                new AgentEvent.ClarificationAccepted(runId(), attemptId(), 9, clarifyAction(),
                        new SessionId("session-1"), clarificationTurn, false),
                new AgentEvent.RunConcluded(runId(), attemptId(), 10, RunOutcome.FAILED, true));
    }

    private static ParticipantRef participant() {
        return new ParticipantRef("test", "participant-1");
    }

    private static AgentEvent.ContextIssued contextIssuedWithDistinctMapAndValueHandles(RunAttempt attempt) {
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>(attempt.issuedCandidates());
        CandidateHandle candidateKey = candidates.keySet().iterator().next();
        IssuedCandidate issuedCandidate = candidates.get(candidateKey);
        CandidateHandle candidateValueHandle = new CandidateHandle(
                "candidate-value-handle",
                candidateKey.binding(),
                candidateKey.kind());
        candidates.put(candidateKey, new IssuedCandidate(candidateValueHandle, issuedCandidate.candidate()));

        Map<EvidenceHandle, IssuedEvidence> evidence = new LinkedHashMap<>(attempt.issuedEvidence());
        EvidenceHandle evidenceKey = evidence.keySet().iterator().next();
        IssuedEvidence issuedEvidence = evidence.get(evidenceKey);
        EvidenceHandle evidenceValueHandle = new EvidenceHandle("evidence-value-handle", evidenceKey.binding());
        evidence.put(evidenceKey, new IssuedEvidence(evidenceValueHandle, issuedEvidence.evidence()));

        return new AgentEvent.ContextIssued(
                runId(),
                attemptId(),
                2,
                attempt.revisionVector(),
                attempt.issuedCapabilities(),
                candidates,
                evidence,
                attempt.observations());
    }

    private static AgentRunState initialState() {
        return AgentRunState.initial(runId(), attemptId(), budget(), requestIdentity());
    }

    private static AgentRunState activeState() {
        RunAttempt attempt = richAttempt();
        return new AgentRunState(runId(), AgentRunStatus.RUNNING, attempt, 1, budget(), 2, 1, 7,
                Optional.empty(), Optional.empty(), Optional.empty(), requestIdentity());
    }

    private static AgentRunState concludedAnswerState() {
        AnswerDocument document = answerDocument();
        ConversationTurn turn = new ConversationTurn(runId(), participant(), "question", document.renderParagraphs(), ConversationTurnType.ANSWER);
        PendingTerminalResponse pending = new PendingTerminalResponse.Answer(new SessionId("session-1"), turn,
                document, AnswerAcceptance.llm(acceptedVerdict()));
        return new AgentRunState(runId(), AgentRunStatus.CONCLUDED, richAttempt(), 1, budget(), 3, 1, 8,
                Optional.of(RunOutcome.COMPLETED), Optional.of(pending), Optional.empty(), requestIdentity());
    }

    private static AgentRunState pendingVerificationState() {
        return new AgentRunState(runId(), AgentRunStatus.RUNNING, richAttempt(), 1, budget(), 2, 1, 7,
                Optional.empty(), Optional.empty(), Optional.of(pendingVerification()), requestIdentity());
    }

    private static PendingAnswerVerification pendingVerification() {
        return new PendingAnswerVerification(attemptId(), revisions(), answerDocument(), false, AnswerVerificationMode.LLM);
    }

    private static AgentRunState concludedClarificationState() {
        ConversationTurn turn = new ConversationTurn(runId(), participant(), "question", "Which route applies?", ConversationTurnType.CLARIFICATION);
        PendingTerminalResponse pending = new PendingTerminalResponse.Clarification(new SessionId("session-1"), turn,
                clarifyAction());
        return new AgentRunState(runId(), AgentRunStatus.CONCLUDED, richAttempt(), 1, budget(), 3, 1, 8,
                Optional.of(RunOutcome.INCONCLUSIVE), Optional.of(pending), Optional.empty(), requestIdentity());
    }

    private static RunAttempt richAttempt() {
        RevisionVector revisions = revisions();
        HandleBinding binding = new HandleBinding(runId(), attemptId(), revisions);
        CapabilityHandle capabilityHandle = new CapabilityHandle("capability-1", binding);
        CandidateHandle repositoryHandle = new CandidateHandle("candidate-repository", binding, CandidateKind.REPOSITORY);
        CandidateHandle routeHandle = new CandidateHandle("candidate-route", binding, CandidateKind.ROUTE);
        CandidateHandle targetHandle = new CandidateHandle("candidate-target", binding, CandidateKind.SEMANTIC_TARGET);
        EvidenceHandle evidenceHandle = new EvidenceHandle("evidence-1", binding);
        Map<CapabilityHandle, CapabilityDescriptor> capabilities = new LinkedHashMap<>();
        capabilities.put(capabilityHandle, capability());
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        candidates.put(repositoryHandle, new IssuedCandidate(repositoryHandle, new RepositoryCandidate(repositoryId(), "repository candidate")));
        candidates.put(routeHandle, new IssuedCandidate(routeHandle,
                new RouteCandidate(repositoryId(), revision(), "GET /orders/{id}", "route candidate")));
        candidates.put(targetHandle, new IssuedCandidate(targetHandle,
                new SemanticTargetCandidate(repositoryId(), revision(), target(), "semantic target candidate")));
        Map<EvidenceHandle, IssuedEvidence> evidence = new LinkedHashMap<>();
        evidence.put(evidenceHandle, new IssuedEvidence(evidenceHandle, evidence()));
        Map<ObservationId, AgentObservation> observations = new LinkedHashMap<>();
        observations.put(new ObservationId("observation-1"), new AgentObservation(new ObservationId("observation-1"),
                ObservationSource.CAPABILITY_EXECUTOR, ObservationCode.PARTIAL_GRAPH, "partial graph", Set.of(routeHandle),
                Set.of(evidenceHandle), "semantic-service"));
        return new RunAttempt(attemptId(), revisions, capabilities, candidates, evidence, observations);
    }

    private static CapabilityDescriptor capability() {
        return new CapabilityDescriptor("inspect", "v1", Set.of(CandidateKind.REPOSITORY, CandidateKind.ROUTE), 1, 3,
                new CapabilityQuerySchema(List.of(
                        new ArgumentDefinition("query", ArgumentType.TEXT, true, null, null, Set.of()),
                        new ArgumentDefinition("limit", ArgumentType.INTEGER, false, 1, 10, Set.of()),
                        new ArgumentDefinition("exact", ArgumentType.BOOLEAN, false, null, null, Set.of()),
                        new ArgumentDefinition("mode", ArgumentType.ENUM, false, null, null, Set.of("direct", "transitive")))));
    }

    private static QueryAction queryAction() {
        RunAttempt attempt = richAttempt();
        CapabilityHandle capability = attempt.issuedCapabilities().keySet().iterator().next();
        return new QueryAction(capability, List.copyOf(attempt.issuedCandidates().keySet()), "Which call handles it?",
                Map.of("query", "order", "limit", "2", "exact", "true", "mode", "direct"), "trace the call graph");
    }

    private static ClarifyAction clarifyAction() {
        CandidateHandle candidate = richAttempt().issuedCandidates().keySet().iterator().next();
        return new ClarifyAction("Which route applies?", List.of(candidate), "multiple repository scopes remain");
    }

    private static AgentObservation observation() {
        return new AgentObservation(new ObservationId("observation-standalone"), ObservationSource.RUNTIME,
                ObservationCode.ACTION_REJECTED, "action was rejected", Set.of(), Set.of(), "runtime");
    }

    private static EvidenceRef evidence() {
        return new EvidenceRef("semantic-service", repositoryId(), revision(), target(), "evidence content",
                List.of(new EvidenceWarning("PARTIAL", "analysis is partial")), new ArtifactRef("sha256:abc"));
    }

    private static SemanticTarget target() {
        return new SemanticTarget(SemanticTargetKind.SYMBOL, "com.example.OrderService#find",
                Optional.of(new SourceRange("src/main/java/OrderService.java", 10, 2, 12, 5)));
    }

    private static AnswerDocument answerDocument() {
        EvidenceHandle citation = new EvidenceHandle("evidence-1", new HandleBinding(runId(), attemptId(), revisions()));
        return new AnswerDocument(List.of(
                new AnswerStatement(new StatementId("statement-1"), StatementType.FACT, "The route calls the service.",
                        Optional.of(new ClaimId("claim-1")), Set.of(citation), Set.of(new ObservationId("observation-1"))),
                new AnswerStatement(new StatementId("statement-2"), StatementType.UNCERTAINTY, "One edge is partial.",
                        Optional.empty(), Set.of(), Set.of(new ObservationId("observation-1")))));
    }

    private static AnswerVerdict acceptedVerdict() {
        return new AnswerVerdict(AnswerDisposition.ACCEPTED_COMPLETE,
                List.of(new StatementVerdict(new StatementId("statement-1"), StatementVerdictStatus.SUPPORTED, "supported"),
                        new StatementVerdict(new StatementId("statement-2"), StatementVerdictStatus.SUPPORTED, "disclosed")),
                List.of(), List.of("partial edge disclosed"), List.of());
    }

    private static AnswerVerdict rejectedVerdict() {
        return new AnswerVerdict(AnswerDisposition.REJECTED, List.of(), List.of(), List.of(), List.of("rewrite"));
    }

    private static RevisionVector revisions() {
        return RevisionVector.empty().pin(repositoryId(), revision());
    }

    private static RepositoryId repositoryId() {
        return new RepositoryId("orders");
    }

    private static RepositoryRevision revision() {
        return new RepositoryRevision("abc123");
    }

    private static AnalysisRunId runId() {
        return new AnalysisRunId("run-1");
    }

    private static AnalysisAttemptId attemptId() {
        return new AnalysisAttemptId("attempt-1");
    }

    private static AttemptBudget budget() {
        return new AttemptBudget(5, 1, 4, 1, 3, 1, 2, 0, 2, 1);
    }

    private static RunRequestIdentity requestIdentity() {
        return new RunRequestIdentity("session-1", participant(), "question");
    }
}
