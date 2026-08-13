package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerAcceptance;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.candidate.FollowUpCandidate;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.candidate.RepositoryCandidate;
import com.java.system.agent.answering.domain.candidate.RouteCandidate;
import com.java.system.agent.answering.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.NeedResolution;
import com.java.system.agent.answering.domain.plan.NeedResolutionStatus;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.PendingAnswerVerification;
import com.java.system.agent.answering.domain.run.PendingTerminalResponse;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 狀態與事件持久化文件的 fail-closed 邊界測試
 */
class AgentPersistenceCodecTest {

    private final AgentStateDocumentCodec stateCodec = new AgentStateDocumentCodec(new ObjectMapper());
    private final AgentEventDocumentCodec eventCodec = new AgentEventDocumentCodec(new ObjectMapper());

    @Test
    void writes_and_reads_only_schema_fifteen_state_documents() {
        AgentRunState state = AgentRunState.initial(runId(), attemptId(), budget(), identity());

        VersionedJsonDocument document = stateCodec.encode(state);

        assertThat(document.schemaVersion()).isEqualTo(15);
        assertThat(stateCodec.decode(document)).isEqualTo(state);
        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(14, document.payload())))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document schema version");
    }

    @Test
    void preserves_model_action_history_in_state_and_action_result_events() {
        QueryAction selectedAction = new QueryAction(
                new CapabilityHandle("capability-1", new HandleBinding(runId(), attemptId(), RevisionVector.empty())),
                "question", new CapabilityInputPayload("{}"), "reason");
        ActionResult result = new ActionResult.ActionInterrupted(
                "RECOVERY_INTERRUPTED", "Selected action outcome was not durably known when execution resumed");
        List<ModelInteraction> interactions = List.of(
                new ModelInteraction.ActionSelected(attemptId(), selectedAction),
                new ModelInteraction.ActionResultRecorded(attemptId(), result));
        AgentRunState state = new AgentRunState(
                runId(), AgentRunStatus.RUNNING,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(),
                1, budget(), 0, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), identity(),
                interactions);
        AgentEvent event = new AgentEvent.ActionResultRecorded(runId(), attemptId(), 1, result);

        VersionedJsonDocument stateDocument = stateCodec.encode(state);

        assertThat(stateDocument.payload().path("model_interactions").path(0).path("action").has("candidates"))
                .isFalse();
        assertThat(stateCodec.decode(stateDocument).modelInteractions()).containsExactlyElementsOf(interactions);
        assertThat(eventCodec.decode(eventCodec.eventType(event), eventCodec.encode(event))).isEqualTo(event);
    }

    @Test
    void round_trips_complete_model_action_history() {
        AnalysisAttemptId secondAttemptId = attemptId("attempt-2");
        AnalysisAttemptId thirdAttemptId = attemptId("attempt-3");
        AnalysisAttemptId fourthAttemptId = attemptId("attempt-4");
        AnalysisAttemptId unresolvedAttemptId = attemptId("attempt-5");
        AnswerDocument answer = new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"),
                StatementType.UNCERTAINTY, "uncertain", Optional.empty(), Set.of(), Set.of())));
        AnswerVerdict rejectedVerdict = new AnswerVerdict(
                AnswerDisposition.REJECTED, List.of(), List.of(), List.of(), List.of("rejected"));
        QueryAction query = queryAction(attemptId());
        ExecuteAction execute = executeAction();
        AnswerAction answerAction = new AnswerAction(answer, List.of(resolution()));
        ClarifyAction clarify = new ClarifyAction("clarify", List.of(), "reason");
        List<ModelInteraction> interactions = List.of(
                new ModelInteraction.MalformedResponse(attemptId(), "unparseable response"),
                new ModelInteraction.ActionSelected(attemptId(), query),
                new ModelInteraction.ActionResultRecorded(attemptId(),
                        new ActionResult.QuerySucceeded(List.of(), List.of("evidence-1", "evidence-1"), List.of())),
                new ModelInteraction.ActionSelected(attemptId(), query),
                new ModelInteraction.ActionResultRecorded(attemptId(),
                        new ActionResult.QueryFailed(List.of("observation-1", "observation-1"), "query failed")),
                new ModelInteraction.ActionSelected(attemptId(), query),
                new ModelInteraction.ActionResultRecorded(attemptId(),
                        new ActionResult.QueryInvalidated("revision changed")),
                new ModelInteraction.ActionSelected(attemptId(), query),
                new ModelInteraction.ActionResultRecorded(attemptId(),
                        new ActionResult.ValidationRejected("INVALID", "invalid action")),
                new ModelInteraction.ActionSelected(secondAttemptId, execute),
                new ModelInteraction.ActionResultRecorded(secondAttemptId,
                        new ActionResult.ExecuteCompleted(ActionResult.ExecuteOutcome.NOT_IMPLEMENTED,
                                List.of("observation-2"), "completed")),
                new ModelInteraction.ActionSelected(thirdAttemptId, answerAction),
                new ModelInteraction.ActionResultRecorded(thirdAttemptId, new ActionResult.AnswerRejected(rejectedVerdict)),
                new ModelInteraction.ActionSelected(thirdAttemptId, answerAction),
                new ModelInteraction.ActionResultRecorded(thirdAttemptId, new ActionResult.AnswerAccepted()),
                new ModelInteraction.ActionSelected(fourthAttemptId, clarify),
                new ModelInteraction.ActionResultRecorded(fourthAttemptId, new ActionResult.ClarificationAccepted()),
                new ModelInteraction.ActionSelected(unresolvedAttemptId, queryAction(unresolvedAttemptId)));
        AgentRunState state = new AgentRunState(
                runId(), AgentRunStatus.RUNNING,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(),
                1, budget(), 0, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), identity(),
                interactions);

        assertThat(stateCodec.decode(stateCodec.encode(state))).isEqualTo(state);
    }

    @Test
    void round_trips_action_selected_and_result_recorded_events() {
        ClarifyAction clarification = new ClarifyAction("clarify", List.of(), "reason");
        List<AgentEvent> events = List.of(
                new AgentEvent.ActionSelected(runId(), attemptId(), 1, clarification),
                new AgentEvent.ActionResultRecorded(runId(), attemptId(), 2, new ActionResult.ClarificationAccepted()));

        for (AgentEvent event : events) {
            assertThat(eventCodec.decode(eventCodec.eventType(event), eventCodec.encode(event))).isEqualTo(event);
        }
    }

    @Test
    void round_trips_a_question_plan_state_action_result_and_event_at_the_new_schemas() {
        QuestionPlan plan = plan();
        PlanAction action = new PlanAction(plan);
        List<ModelInteraction> interactions = List.of(
                new ModelInteraction.ActionSelected(attemptId(), action),
                new ModelInteraction.ActionResultRecorded(attemptId(), new ActionResult.QuestionPlanRecorded(plan)));
        AgentRunState state = new AgentRunState(
                runId(), AgentRunStatus.RUNNING,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(),
                1, budget(), 1, 0, 2,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(plan),
                identity(), interactions);
        AgentEvent event = new AgentEvent.QuestionPlanCreated(runId(), attemptId(), 2, plan);

        VersionedJsonDocument stateDocument = stateCodec.encode(state);
        VersionedJsonDocument eventDocument = eventCodec.encode(event);

        assertThat(stateDocument.schemaVersion()).isEqualTo(15);
        assertThat(eventDocument.schemaVersion()).isEqualTo(13);
        assertThat(stateCodec.decode(stateDocument)).isEqualTo(state);
        assertThat(eventCodec.decode(eventCodec.eventType(event), eventDocument)).isEqualTo(event);
        assertThatThrownBy(() -> stateCodec.decode(14, stateDocument.payload()))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported state document schema version");
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 12, eventDocument.payload()))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported event document schema version");
    }

    @Test
    void exposes_not_implemented_as_the_current_closed_execute_outcome() {
        assertThat(ActionResult.ExecuteOutcome.valueOf("NOT_IMPLEMENTED").name()).isEqualTo("NOT_IMPLEMENTED");
    }

    @Test
    void rejects_unknown_model_interaction_and_action_result_discriminators_and_fields() {
        QueryAction query = queryAction(attemptId());
        AgentRunState state = new AgentRunState(
                runId(), AgentRunStatus.RUNNING,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(),
                1, budget(), 0, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), identity(),
                List.of(
                        new ModelInteraction.ActionSelected(attemptId(), query),
                        new ModelInteraction.ActionResultRecorded(attemptId(),
                                new ActionResult.QuerySucceeded(List.of(), List.of(), List.of()))));
        VersionedJsonDocument stateDocument = stateCodec.encode(state);
        ObjectNode unknownInteraction = stateDocument.payload().deepCopy();
        ((ObjectNode) unknownInteraction.path("model_interactions").path(0))
                .put("interaction_type", "UNKNOWN_INTERACTION");
        assertThatThrownBy(() -> stateCodec.decode(15, unknownInteraction))
                .isInstanceOf(PersistenceDocumentException.class);

        ObjectNode unknownInteractionField = stateDocument.payload().deepCopy();
        ((ObjectNode) unknownInteractionField.path("model_interactions").path(0)).put("unexpected", true);
        assertThatThrownBy(() -> stateCodec.decode(15, unknownInteractionField))
                .isInstanceOf(PersistenceDocumentException.class);

        AgentEvent event = new AgentEvent.ActionResultRecorded(
                runId(), attemptId(), 1, new ActionResult.QuerySucceeded(List.of(), List.of(), List.of()));
        VersionedJsonDocument eventDocument = eventCodec.encode(event);
        ObjectNode unknownResult = eventDocument.payload().deepCopy();
        ((ObjectNode) unknownResult.path("result")).put("result_type", "UNKNOWN_RESULT");
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), eventDocument.schemaVersion(), unknownResult))
                .isInstanceOf(PersistenceDocumentException.class);

        ObjectNode unknownResultField = eventDocument.payload().deepCopy();
        ((ObjectNode) unknownResultField.path("result")).put("unexpected", true);
        assertThatThrownBy(() -> eventCodec.decode(
                eventCodec.eventType(event), eventDocument.schemaVersion(), unknownResultField))
                .isInstanceOf(PersistenceDocumentException.class);
    }

    @Test
    void isolates_persistence_schema_from_host_object_mapper_modules() {
        ObjectMapper hostObjectMapper = new ObjectMapper();
        SimpleModule hostModule = new SimpleModule();
        hostModule.addSerializer(AnalysisRunId.class, new JsonSerializer<>() {
            @Override
            public void serialize(
                    AnalysisRunId value,
                    JsonGenerator generator,
                    SerializerProvider serializers) throws IOException {
                generator.writeString("host-overridden-run-id");
            }
        });
        hostObjectMapper.registerModule(hostModule);
        AgentStateDocumentCodec isolatedCodec = new AgentStateDocumentCodec(hostObjectMapper);
        AgentRunState state = AgentRunState.initial(runId(), attemptId(), budget(), identity());

        VersionedJsonDocument document = isolatedCodec.encode(state);

        assertThat(document.payload().path("run_id").path("value").asText()).isEqualTo("run-1");
        assertThat(isolatedCodec.decode(document)).isEqualTo(state);
    }

    @Test
    void writes_terminal_reasons_and_execute_budget_fields() {
        AgentEvent event = new AgentEvent.RunConcluded(runId(), attemptId(), 0, RunOutcome.INCONCLUSIVE,
                Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED), Optional.empty());

        VersionedJsonDocument eventDocument = eventCodec.encode(event);
        ObjectNode state = (ObjectNode) stateCodec.encode(AgentRunState.initial(runId(), attemptId(), budget(), identity()))
                .payload();
        ObjectNode serializedBudget = (ObjectNode) state.path("budget");

        assertThat(eventDocument.schemaVersion()).isEqualTo(13);
        assertThat(eventDocument.payload().path("runtime_notice_reason").asText())
                .isEqualTo("AGENT_STEP_BUDGET_EXHAUSTED");
        assertThat(eventCodec.decode(eventCodec.eventType(event), eventDocument)).isEqualTo(event);
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 12, eventDocument.payload()))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("unsupported event document schema version");
        assertThat(serializedBudget.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "max_agent_steps", "used_agent_steps", "max_query_executions", "used_query_executions",
                "max_execute_executions", "used_execute_executions",
                "max_action_rejections", "used_action_rejections", "max_revision_restarts",
                "used_revision_restarts");
        assertThat(serializedBudget.path("max_execute_executions").intValue()).isEqualTo(1);
        assertThat(serializedBudget.path("used_execute_executions").intValue()).isZero();
    }

    @Test
    void writes_and_reads_a_failed_http_mutation_contract_reason() {
        AgentEvent event = new AgentEvent.RunConcluded(runId(), attemptId(), 0, RunOutcome.FAILED,
                Optional.empty(), Optional.of(RunFailureReason.HTTP_MUTATION_CONTRACT));

        VersionedJsonDocument document = eventCodec.encode(event);

        assertThat(document.payload().path("failure_reason").asText()).isEqualTo("HTTP_MUTATION_CONTRACT");
        assertThat(eventCodec.decode(eventCodec.eventType(event), document)).isEqualTo(event);
    }

    @Test
    void preserves_terminal_and_pending_verification_state_variants() {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"),
                StatementType.UNCERTAINTY, "uncertain", Optional.empty(), Set.of(), Set.of())));
        ConversationTurn answerTurn = new ConversationTurn(
                runId(), identity().participant(), "question", document.renderParagraphs(), ConversationTurnType.ANSWER);
        PendingTerminalResponse pendingAnswer = new PendingTerminalResponse.Answer(
                new SessionId("session-1"), answerTurn, document, AnswerAcceptance.contractOnly());
        AgentRunState terminal = new AgentRunState(runId(), AgentRunStatus.CONCLUDED,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(), 1, budget(), 0, 0, 1,
                Optional.of(RunOutcome.COMPLETED), Optional.empty(), Optional.empty(), Optional.of(pendingAnswer),
                Optional.empty(), Optional.empty(), identity(), List.of());
        ClarifyAction clarification = new ClarifyAction("clarify", List.of(), "reason");
        ConversationTurn clarificationTurn = new ConversationTurn(
                runId(), identity().participant(), "question", "clarify", ConversationTurnType.CLARIFICATION);
        PendingTerminalResponse pendingClarification = new PendingTerminalResponse.Clarification(
                new SessionId("session-1"), clarificationTurn, clarification);
        AgentRunState clarificationTerminal = new AgentRunState(runId(), AgentRunStatus.CONCLUDED,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(), 1, budget(), 0, 0, 1,
                Optional.of(RunOutcome.INCONCLUSIVE), Optional.empty(), Optional.empty(),
                Optional.of(pendingClarification), Optional.empty(), Optional.empty(), identity(), List.of());
        PendingAnswerVerification pending = new PendingAnswerVerification(attemptId(), RevisionVector.empty(), new AnswerAction(document, List.of(resolution())),
                AnswerVerificationMode.LLM);
        AgentRunState verificationPending = new AgentRunState(runId(), AgentRunStatus.RUNNING,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(), 1, budget(), 0, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(pending), Optional.empty(), identity(), List.of());

        assertThat(stateCodec.decode(stateCodec.encode(terminal))).isEqualTo(terminal);
        assertThat(stateCodec.decode(stateCodec.encode(clarificationTerminal))).isEqualTo(clarificationTerminal);
        assertThat(stateCodec.decode(stateCodec.encode(verificationPending))).isEqualTo(verificationPending);
        AgentEvent event = new AgentEvent.AnswerProposed(runId(), attemptId(), 1, pending);
        assertThat(eventCodec.decode(eventCodec.eventType(event), eventCodec.encode(event))).isEqualTo(event);
    }

    @Test
    void rejects_contract_only_pending_verification_before_recovery() {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"),
                StatementType.UNCERTAINTY, "uncertain", Optional.empty(), Set.of(), Set.of())));
        PendingAnswerVerification pending = new PendingAnswerVerification(
                attemptId(), RevisionVector.empty(), new AnswerAction(document, List.of(resolution())),
                AnswerVerificationMode.LLM);
        AgentRunState state = new AgentRunState(runId(), AgentRunStatus.RUNNING,
                AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt(), 1, budget(), 0, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(pending), Optional.empty(),
                identity(), List.of());
        VersionedJsonDocument documentPayload = stateCodec.encode(state);
        ObjectNode malformed = documentPayload.payload().deepCopy();
        ((ObjectNode) malformed.path("pending_answer_verification")).put("verification_mode", "CONTRACT_ONLY");

        assertThatThrownBy(() -> stateCodec.decode(new VersionedJsonDocument(15, malformed)))
                .isInstanceOf(PersistenceDocumentException.class);
    }

    @Test
    void preserves_every_event_discriminator() {
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"),
                StatementType.UNCERTAINTY, "uncertain", Optional.empty(), Set.of(), Set.of())));
        PendingAnswerVerification proposal = new PendingAnswerVerification(
                attemptId(), RevisionVector.empty(), new AnswerAction(document, List.of(resolution())), AnswerVerificationMode.LLM);
        ConversationTurn answerTurn = new ConversationTurn(
                runId(), identity().participant(), "question", document.renderParagraphs(), ConversationTurnType.ANSWER);
        ClarifyAction clarification = new ClarifyAction("clarify", List.of(), "reason");
        ConversationTurn clarificationTurn = new ConversationTurn(
                runId(), identity().participant(), "question", "clarify", ConversationTurnType.CLARIFICATION);
        AgentObservation observation = new AgentObservation(
                new ObservationId("observation-1"),
                ObservationSource.HTTP_MUTATION,
                ObservationCode.EXECUTION_NOT_IMPLEMENTED,
                "description",
                Set.of(),
                Set.of(),
                "runtime");
        AnswerVerdict rejected = new AnswerVerdict(
                AnswerDisposition.REJECTED, List.of(), List.of(), List.of(), List.of("rejected"));
        List<AgentEvent> events = List.of(
                new AgentEvent.RunStarted(runId(), attemptId(), 0),
                new AgentEvent.AttemptStarted(
                        runId(), attemptId(), 0,
                        AgentRunState.initial(runId(), attemptId(), budget(), identity()).currentAttempt()),
                new AgentEvent.ContextIssued(
                        runId(), attemptId(), 0, RevisionVector.empty(), Map.of(), Map.of(), Map.of(), Map.of()),
                new AgentEvent.QuestionPlanCreated(runId(), attemptId(), 0, plan()),
                new AgentEvent.ActionAccepted(runId(), attemptId(), 0, executeAction()),
                new AgentEvent.ActionRejected(runId(), attemptId(), 0, Optional.of(executeAction()),
                        "INVALID_EXECUTE_TARGET", "rejected"),
                new AgentEvent.QueryBudgetConsumed(runId(), attemptId(), 0),
                new AgentEvent.ExecuteBudgetConsumed(runId(), attemptId(), 0),
                new AgentEvent.ObservationRecorded(runId(), attemptId(), 0, observation),
                new AgentEvent.AttemptInvalidated(runId(), attemptId(), 0, "revision changed", true),
                new AgentEvent.AnswerProposed(runId(), attemptId(), 0, proposal),
                new AgentEvent.AnswerAccepted(
                        runId(), attemptId(), 0, new AnswerAction(document, List.of(resolution())), AnswerAcceptance.contractOnly(),
                        new SessionId("session-1"), answerTurn),
                new AgentEvent.AnswerRejected(runId(), attemptId(), 0, rejected),
                new AgentEvent.AnswerVerificationAbandoned(
                        runId(), attemptId(), 0, AnswerVerificationAbandonReason.RETRY_EXHAUSTED),
                new AgentEvent.ClarificationAccepted(
                        runId(), attemptId(), 0, clarification, new SessionId("session-1"), clarificationTurn),
                new AgentEvent.RunConcluded(
                        runId(), attemptId(), 0, RunOutcome.FAILED,
                        Optional.empty(), Optional.of(RunFailureReason.HTTP_MUTATION_CONTRACT)));

        for (AgentEvent event : events) {
            assertThat(eventCodec.decode(eventCodec.eventType(event), eventCodec.encode(event))).isEqualTo(event);
        }
    }

    @Test
    void preserves_all_action_discriminators() {
        HandleBinding binding = new HandleBinding(runId(), attemptId(), RevisionVector.empty());
        AnswerDocument document = new AnswerDocument(List.of(new AnswerStatement(new StatementId("statement-1"),
                StatementType.UNCERTAINTY, "uncertain", Optional.empty(), Set.of(), Set.of())));
        List<AgentAction> actions = List.of(
                new QueryAction(new CapabilityHandle("capability-1", binding), "question",
                        new CapabilityInputPayload("{}"), "reason"),
                new AnswerAction(document, List.of(resolution())),
                new ClarifyAction("clarify", List.of(), "reason"),
                executeAction(),
                new PlanAction(plan())
        );

        for (AgentAction action : actions) {
            AgentEvent event = new AgentEvent.ActionAccepted(runId(), attemptId(), 0, action);
            assertThat(eventCodec.decode(eventCodec.eventType(event), eventCodec.encode(event))).isEqualTo(event);
        }
    }

    @Test
    void rejects_unknown_missing_null_and_coerced_event_properties() {
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"attempt_id":{"value":"attempt-1"},"expected_state_revision":0,"unexpected":true}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"attempt_id":{"value":"attempt-1"}}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":null,"attempt_id":{"value":"attempt-1"},"expected_state_revision":0}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"attempt_id":{"value":"attempt-1"},"expected_state_revision":null}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"attempt_id":{"value":"attempt-1"},"expected_state_revision":"0"}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"attempt_id":{"value":"attempt-1"},"expected_state_revision":0.5}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":"","attempt_id":{"value":"attempt-1"},"expected_state_revision":0}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
    }

    @Test
    void rejects_duplicate_trailing_and_relationally_mismatched_event_discriminators() {
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"run_id":{"value":"run-2"},"attempt_id":{"value":"attempt-1"},"expected_state_revision":0}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_STARTED", 12,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"attempt_id":{"value":"attempt-1"},"expected_state_revision":0} {}
                        """))
                .isInstanceOf(PersistenceDocumentException.class);
        assertThatThrownBy(() -> eventCodec.decode("RUN_CONCLUDED", 13,
                """
                        {"event_type":"RUN_STARTED","run_id":{"value":"run-1"},"attempt_id":{"value":"attempt-1"},"expected_state_revision":0}
                        """))
                .isInstanceOf(PersistenceDocumentException.class)
                .hasMessage("relational event type does not match event payload");
    }

    @Test
    void round_trips_follow_up_candidates_with_their_canonical_payload() {
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(runId(), attemptId(), revisions);
        FollowUpCandidate followUp = new FollowUpCandidate(
                repositoryId,
                revision,
                "codebase_get_source_segment",
                "v1",
                new CapabilityInputPayload("{\"contextLines\":0,\"location\":{\"sourceFile\":\"Example.java\"}}"),
                "Read the next bounded source segment");
        CandidateHandle followUpHandle = new CandidateHandle("candidate-1", binding, CandidateKind.FOLLOW_UP);
        Map<CandidateHandle, IssuedCandidate> candidates = Map.of(
                followUpHandle, new IssuedCandidate(followUpHandle, followUp));
        RunAttempt attempt = new RunAttempt(attemptId(), revisions, Map.of(), candidates, Map.of(), Map.of());
        AgentRunState state = new AgentRunState(
                runId(), AgentRunStatus.RUNNING, attempt, 1, budget(), 0, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), identity(), List.of());
        AgentEvent.ContextIssued event = new AgentEvent.ContextIssued(
                runId(), attemptId(), 1, revisions, Map.of(), candidates, Map.of(), Map.of());

        VersionedJsonDocument stateDocument = stateCodec.encode(state);
        VersionedJsonDocument eventDocument = eventCodec.encode(event);

        assertThat(stateDocument.schemaVersion()).isEqualTo(15);
        assertThat(eventDocument.schemaVersion()).isEqualTo(13);
        assertThat(stateCodec.decode(stateDocument)).isEqualTo(state);
        assertThat(eventCodec.decode(eventCodec.eventType(event), eventDocument)).isEqualTo(event);
        assertThat(eventDocument.payload().path("candidates").path(0).path("value").path("candidate")
                .path("payload").path("value").asText()).isEqualTo(followUp.payload().value());

        ObjectNode unknownCandidateType = eventDocument.payload().deepCopy();
        ((ObjectNode) unknownCandidateType.path("candidates").path(0).path("value").path("candidate"))
                .put("candidate_type", "UNKNOWN");
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 13, unknownCandidateType))
                .isInstanceOf(PersistenceDocumentException.class);

        ObjectNode blankTargetCapability = eventDocument.payload().deepCopy();
        ((ObjectNode) blankTargetCapability.path("candidates").path(0).path("value").path("candidate"))
                .put("target_capability_name", " ");
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 13, blankTargetCapability))
                .isInstanceOf(PersistenceDocumentException.class);
    }

    @Test
    void rejects_duplicate_map_and_set_entries_before_domain_construction() {
        RepositoryId repositoryId = new RepositoryId("repository-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        RevisionVector revisions = RevisionVector.empty().pin(repositoryId, revision);
        HandleBinding binding = new HandleBinding(runId(), attemptId(), revisions);
        CapabilityHandle handle = new CapabilityHandle("capability-1", binding);
        CapabilityPolicy policy = new CapabilityPolicy(
                "lookup", "v1", Set.of(CandidateKind.REPOSITORY), 0, 1);
        CandidateHandle repositoryHandle = new CandidateHandle("candidate-1", binding, CandidateKind.REPOSITORY);
        CandidateHandle routeHandle = new CandidateHandle("candidate-2", binding, CandidateKind.ROUTE);
        CandidateHandle semanticHandle = new CandidateHandle("candidate-3", binding, CandidateKind.SEMANTIC_TARGET);
        Map<CandidateHandle, IssuedCandidate> candidates = new LinkedHashMap<>();
        candidates.put(repositoryHandle, new IssuedCandidate(
                repositoryHandle, new RepositoryCandidate(repositoryId, "repository")));
        candidates.put(routeHandle, new IssuedCandidate(
                routeHandle, new RouteCandidate(repositoryId, revision, "/route", "route")));
        candidates.put(semanticHandle, new IssuedCandidate(semanticHandle, new SemanticTargetCandidate(
                repositoryId, revision,
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Example", Optional.empty()),
                "semantic target")));
        AgentObservation observation = new AgentObservation(
                new ObservationId("observation-1"), ObservationSource.RUNTIME, ObservationCode.ACTION_REJECTED,
                "description", Set.of(), Set.of(), "runtime");
        AgentEvent event = new AgentEvent.ContextIssued(runId(), attemptId(), 0, revisions,
                Map.of(handle, policy), candidates, Map.of(), Map.of(observation.id(), observation));
        VersionedJsonDocument document = eventCodec.encode(event);

        AgentEvent.ContextIssued decoded = (AgentEvent.ContextIssued) eventCodec.decode(
                eventCodec.eventType(event), document);
        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.candidates().keySet()).containsExactly(repositoryHandle, routeHandle, semanticHandle);

        ObjectNode duplicateMap = document.payload().deepCopy();
        ArrayNode capabilities = (ArrayNode) duplicateMap.path("capabilities");
        capabilities.add(capabilities.get(0).deepCopy());
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 13, duplicateMap))
                .isInstanceOf(PersistenceDocumentException.class);

        ObjectNode mismatchedObservation = document.payload().deepCopy();
        ObjectNode observationKey = (ObjectNode) mismatchedObservation.path("observations").path(0).path("key");
        observationKey.put("value", "observation-2");
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 13, mismatchedObservation))
                .isInstanceOf(PersistenceDocumentException.class);

        ObjectNode duplicateRevision = document.payload().deepCopy();
        ArrayNode revisionEntries = (ArrayNode) duplicateRevision.path("revisions").path("entries");
        revisionEntries.add(revisionEntries.get(0).deepCopy());
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 13, duplicateRevision))
                .isInstanceOf(PersistenceDocumentException.class);

        ObjectNode duplicateSet = document.payload().deepCopy();
        ArrayNode acceptedKinds = (ArrayNode) duplicateSet.path("capabilities").path(0)
                .path("value").path("accepted_candidate_kinds");
        acceptedKinds.add("REPOSITORY");
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(event), 13, duplicateSet))
                .isInstanceOf(PersistenceDocumentException.class);

        AgentObservation observationWithCandidate = new AgentObservation(
                new ObservationId("observation-2"), ObservationSource.RUNTIME, ObservationCode.ACTION_REJECTED,
                "description", Set.of(repositoryHandle), Set.of(), "runtime");
        AgentEvent observationEvent = new AgentEvent.ObservationRecorded(
                runId(), attemptId(), 0, observationWithCandidate);
        ObjectNode duplicateObservationSet = eventCodec.encode(observationEvent).payload().deepCopy();
        ArrayNode candidateHandles = (ArrayNode) duplicateObservationSet.path("observation").path("candidate_handles");
        candidateHandles.add(candidateHandles.get(0).deepCopy());
        assertThatThrownBy(() -> eventCodec.decode(
                eventCodec.eventType(observationEvent), 13, duplicateObservationSet))
                .isInstanceOf(PersistenceDocumentException.class);

        AnswerDocument answer = new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.UNCERTAINTY, "uncertain", Optional.empty(), Set.of(),
                Set.of(new ObservationId("observation-1")))));
        AgentEvent answerEvent = new AgentEvent.ActionAccepted(runId(), attemptId(), 0, new AnswerAction(answer, List.of()));
        ObjectNode duplicateAnswerSet = eventCodec.encode(answerEvent).payload().deepCopy();
        ArrayNode observationIds = (ArrayNode) duplicateAnswerSet.path("action").path("document")
                .path("statements").path(0).path("observation_ids");
        observationIds.add(observationIds.get(0).deepCopy());
        assertThatThrownBy(() -> eventCodec.decode(eventCodec.eventType(answerEvent), 13, duplicateAnswerSet))
                .isInstanceOf(PersistenceDocumentException.class);
    }

    private static AnalysisRunId runId() {
        return new AnalysisRunId("run-1");
    }

    private static NeedResolution resolution() {
        return new NeedResolution(new InformationNeedId("need-1"), NeedResolutionStatus.UNAVAILABLE,
                Set.of(), Set.of(new ObservationId("observation-1")));
    }

    private static AnalysisAttemptId attemptId() {
        return new AnalysisAttemptId("attempt-1");
    }

    private static AnalysisAttemptId attemptId(String value) {
        return new AnalysisAttemptId(value);
    }

    private static AttemptBudget budget() {
        return new AttemptBudget(3, 0, 2, 0, 1, 0, 2, 0, 1, 0);
    }

    private static ExecuteAction executeAction() {
        return new ExecuteAction(ExternalHttpMethod.POST, "https://example.test/mutations", Optional.of("{}"),
                "apply preview mutation");
    }

    private static QueryAction queryAction(AnalysisAttemptId attemptId) {
        HandleBinding binding = new HandleBinding(runId(), attemptId, RevisionVector.empty());
        return new QueryAction(new CapabilityHandle("capability-1", binding), "question",
                new CapabilityInputPayload("{}"), "reason");
    }

    private static QuestionPlan plan() {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("need-1"), "Trace the route")));
    }

    private static RunRequestIdentity identity() {
        return new RunRequestIdentity("session-1", new ParticipantRef("test", "participant"), "question",
                new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"));
    }
}
