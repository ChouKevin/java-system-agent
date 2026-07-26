package com.java.system.agent.persistence.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.java.system.agent.runtime.domain.action.AgentAction;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.domain.capability.ArgumentDefinition;
import com.java.system.agent.runtime.domain.capability.ArgumentType;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.candidate.AnalysisCandidate;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.IssuedCandidate;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.candidate.RouteCandidate;
import com.java.system.agent.runtime.domain.candidate.SemanticTargetCandidate;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
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
import com.java.system.agent.runtime.domain.observation.SemanticObservation;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentRunStatus;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.PendingTerminalResponse;
import com.java.system.agent.runtime.domain.run.RunAttempt;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunRequestIdentity;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * runtime 值物件與穩定 JSON 結構之間的完整且明確對照表
 */
final class AgentValueDocumentMapper {

    private final ObjectMapper objectMapper;

    AgentValueDocumentMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    ObjectNode stateNode(AgentRunState state) {
        ObjectNode node = object();
        node.put("state_type", "AGENT_RUN_STATE");
        node.put("run_id", state.runId().value());
        node.put("status", state.status().name());
        node.set("current_attempt", attemptNode(state.currentAttempt()));
        node.put("attempt_sequence", state.attemptSequence());
        node.set("budget", budgetNode(state.budget()));
        node.put("accepted_action_count", state.acceptedActionCount());
        node.put("rejected_action_count", state.rejectedActionCount());
        node.put("state_revision", state.stateRevision());
        putOptionalEnum(node, "final_outcome", state.finalOutcome());
        putOptionalNode(node, "pending_terminal_response", state.pendingTerminalResponse().map(this::pendingNode));
        node.set("request_identity", requestIdentityNode(state.requestIdentity()));
        return node;
    }

    AgentRunState stateFrom(JsonNode payload) {
        ObjectNode node = object(payload);
        requireExactFields(node, "state_type", "run_id", "status", "current_attempt", "attempt_sequence", "budget",
                "accepted_action_count", "rejected_action_count", "state_revision", "final_outcome",
                "pending_terminal_response", "request_identity");
        requireDiscriminator(node, "state_type", "AGENT_RUN_STATE", "unsupported state document discriminator");
        return new AgentRunState(runId(node, "run_id"), enumField(node, "status", AgentRunStatus.class),
                attemptFrom(required(node, "current_attempt")), intField(node, "attempt_sequence"),
                budgetFrom(required(node, "budget")),
                longField(node, "accepted_action_count"), longField(node, "rejected_action_count"), longField(node, "state_revision"),
                optionalEnum(node, "final_outcome", RunOutcome.class), optionalNode(node, "pending_terminal_response").map(this::pendingFrom),
                requestIdentityFrom(required(node, "request_identity")));
    }

    ObjectNode eventNode(AgentEvent event) {
        ObjectNode node = object();
        String eventType = eventType(event);
        node.put("event_type", eventType);
        node.put("run_id", event.runId().value());
        node.put("attempt_id", event.attemptId().value());
        node.put("expected_state_revision", event.expectedStateRevision());
        switch (event) {
            case AgentEvent.RunStarted ignored -> { }
            case AgentEvent.AttemptStarted value -> node.set("new_attempt", attemptNode(value.newAttempt()));
            case AgentEvent.ContextIssued value -> {
                node.set("revisions", revisionsNode(value.revisions()));
                node.set("capabilities", capabilitiesNode(value.capabilities()));
                node.set("candidates", candidatesNode(value.candidates()));
                node.set("evidence", evidenceNode(value.evidence()));
                node.set("observations", observationsNode(value.observations()));
            }
            case AgentEvent.ActionAccepted value -> node.set("action", actionNode(value.action()));
            case AgentEvent.ActionRejected value -> {
                putOptionalNode(node, "original_action", value.originalAction().map(this::actionNode));
                node.put("description", value.description());
                node.put("final_response_mode", value.finalResponseMode());
            }
            case AgentEvent.QueryBudgetConsumed ignored -> { }
            case AgentEvent.ObservationRecorded value -> node.set("observation", observationNode(value.observation()));
            case AgentEvent.AttemptInvalidated value -> {
                node.put("reason", value.reason());
                node.put("consume_revision_restart", value.consumeRevisionRestart());
            }
            case AgentEvent.AnswerAccepted value -> {
                node.set("document", answerDocumentNode(value.document()));
                node.set("verdict", answerVerdictNode(value.verdict()));
                node.put("session_id", value.sessionId().value());
                node.set("turn", conversationTurnNode(value.turn()));
                node.put("final_response_mode", value.finalResponseMode());
            }
            case AgentEvent.ClarificationAccepted value -> {
                node.set("action", actionNode(value.action()));
                node.put("session_id", value.sessionId().value());
                node.set("turn", conversationTurnNode(value.turn()));
                node.put("final_response_mode", value.finalResponseMode());
            }
            case AgentEvent.RunConcluded value -> {
                node.put("outcome", value.outcome().name());
                node.put("runtime_fixed_response", value.runtimeFixedResponse());
            }
        }
        return node;
    }

    String eventType(AgentEvent event) {
        return switch (event) {
            case AgentEvent.RunStarted ignored -> "RUN_STARTED";
            case AgentEvent.AttemptStarted ignored -> "ATTEMPT_STARTED";
            case AgentEvent.ContextIssued ignored -> "CONTEXT_ISSUED";
            case AgentEvent.ActionAccepted ignored -> "ACTION_ACCEPTED";
            case AgentEvent.ActionRejected ignored -> "ACTION_REJECTED";
            case AgentEvent.QueryBudgetConsumed ignored -> "QUERY_BUDGET_CONSUMED";
            case AgentEvent.ObservationRecorded ignored -> "OBSERVATION_RECORDED";
            case AgentEvent.AttemptInvalidated ignored -> "ATTEMPT_INVALIDATED";
            case AgentEvent.AnswerAccepted ignored -> "ANSWER_ACCEPTED";
            case AgentEvent.ClarificationAccepted ignored -> "CLARIFICATION_ACCEPTED";
            case AgentEvent.RunConcluded ignored -> "RUN_CONCLUDED";
        };
    }

    String eventTypeFrom(JsonNode payload) {
        return text(object(payload), "event_type");
    }

    AgentEvent eventFrom(JsonNode payload, String eventType) {
        ObjectNode node = object(payload);
        AnalysisRunId runId = runId(node, "run_id");
        AnalysisAttemptId attemptId = attemptId(node, "attempt_id");
        long expectedRevision = longField(node, "expected_state_revision");
        return switch (eventType) {
            case "RUN_STARTED" -> {
                requireExactFields(node, "event_type", "run_id", "attempt_id", "expected_state_revision");
                yield new AgentEvent.RunStarted(runId, attemptId, expectedRevision);
            }
            case "ATTEMPT_STARTED" -> {
                requireExactFields(
                        node, "event_type", "run_id", "attempt_id", "expected_state_revision", "new_attempt");
                yield new AgentEvent.AttemptStarted(
                        runId, attemptId, expectedRevision, attemptFrom(required(node, "new_attempt")));
            }
            case "CONTEXT_ISSUED" -> {
                requireExactFields(
                        node,
                        "event_type",
                        "run_id",
                        "attempt_id",
                        "expected_state_revision",
                        "revisions",
                        "capabilities",
                        "candidates",
                        "evidence",
                        "observations");
                yield new AgentEvent.ContextIssued(
                        runId,
                        attemptId,
                        expectedRevision,
                        revisionsFrom(required(node, "revisions")),
                        capabilitiesFrom(required(node, "capabilities")),
                        candidatesFrom(required(node, "candidates")),
                        evidenceFrom(required(node, "evidence")),
                        observationsFrom(required(node, "observations")));
            }
            case "ACTION_ACCEPTED" -> {
                requireExactFields(
                        node, "event_type", "run_id", "attempt_id", "expected_state_revision", "action");
                yield new AgentEvent.ActionAccepted(
                        runId, attemptId, expectedRevision, actionFrom(required(node, "action")));
            }
            case "ACTION_REJECTED" -> {
                requireExactFields(
                        node,
                        "event_type",
                        "run_id",
                        "attempt_id",
                        "expected_state_revision",
                        "original_action",
                        "description",
                        "final_response_mode");
                yield new AgentEvent.ActionRejected(
                        runId,
                        attemptId,
                        expectedRevision,
                        optionalNode(node, "original_action").map(this::actionFrom),
                        text(node, "description"),
                        booleanField(node, "final_response_mode"));
            }
            case "QUERY_BUDGET_CONSUMED" -> {
                requireExactFields(node, "event_type", "run_id", "attempt_id", "expected_state_revision");
                yield new AgentEvent.QueryBudgetConsumed(runId, attemptId, expectedRevision);
            }
            case "OBSERVATION_RECORDED" -> {
                requireExactFields(
                        node, "event_type", "run_id", "attempt_id", "expected_state_revision", "observation");
                yield new AgentEvent.ObservationRecorded(
                        runId,
                        attemptId,
                        expectedRevision,
                        observationFrom(required(node, "observation")));
            }
            case "ATTEMPT_INVALIDATED" -> {
                requireExactFields(
                        node,
                        "event_type",
                        "run_id",
                        "attempt_id",
                        "expected_state_revision",
                        "reason",
                        "consume_revision_restart");
                yield new AgentEvent.AttemptInvalidated(
                        runId,
                        attemptId,
                        expectedRevision,
                        text(node, "reason"),
                        booleanField(node, "consume_revision_restart"));
            }
            case "ANSWER_ACCEPTED" -> {
                requireExactFields(
                        node,
                        "event_type",
                        "run_id",
                        "attempt_id",
                        "expected_state_revision",
                        "document",
                        "verdict",
                        "session_id",
                        "turn",
                        "final_response_mode");
                yield new AgentEvent.AnswerAccepted(
                        runId,
                        attemptId,
                        expectedRevision,
                        answerDocumentFrom(required(node, "document")),
                        answerVerdictFrom(required(node, "verdict")),
                        new SessionId(text(node, "session_id")),
                        conversationTurnFrom(required(node, "turn")),
                        booleanField(node, "final_response_mode"));
            }
            case "CLARIFICATION_ACCEPTED" -> {
                requireExactFields(
                        node,
                        "event_type",
                        "run_id",
                        "attempt_id",
                        "expected_state_revision",
                        "action",
                        "session_id",
                        "turn",
                        "final_response_mode");
                yield new AgentEvent.ClarificationAccepted(
                        runId,
                        attemptId,
                        expectedRevision,
                        clarifyActionFrom(required(node, "action")),
                        new SessionId(text(node, "session_id")),
                        conversationTurnFrom(required(node, "turn")),
                        booleanField(node, "final_response_mode"));
            }
            case "RUN_CONCLUDED" -> {
                requireExactFields(
                        node,
                        "event_type",
                        "run_id",
                        "attempt_id",
                        "expected_state_revision",
                        "outcome",
                        "runtime_fixed_response");
                yield new AgentEvent.RunConcluded(
                        runId,
                        attemptId,
                        expectedRevision,
                        enumField(node, "outcome", RunOutcome.class),
                        booleanField(node, "runtime_fixed_response"));
            }
            default -> throw new PersistenceDocumentException("unsupported event document discriminator");
        };
    }

    ObjectNode semanticObservationNode(SemanticObservation observation) {
        ObjectNode node = object();
        node.put("observation_type", "SEMANTIC_OBSERVATION");
        node.put("code", observation.code().name());
        node.put("description", observation.description());
        ArrayNode candidates = array();
        for (AnalysisCandidate candidate : observation.candidates()) {
            candidates.add(candidateNode(candidate));
        }
        node.set("candidates", candidates);
        ArrayNode evidence = array();
        for (EvidenceRef value : observation.evidence()) {
            evidence.add(evidenceRefNode(value));
        }
        node.set("evidence", evidence);
        node.put("provenance", observation.provenance());
        return node;
    }

    SemanticObservation semanticObservationFrom(JsonNode node) {
        ObjectNode object = object(node);
        requireExactFields(object, "observation_type", "code", "description", "candidates", "evidence", "provenance");
        requireDiscriminator(object, "observation_type", "SEMANTIC_OBSERVATION", "unsupported observation document discriminator");
        List<AnalysisCandidate> candidates = new ArrayList<>();
        for (JsonNode candidate : array(required(object, "candidates"))) {
            candidates.add(candidateFrom(candidate));
        }
        List<EvidenceRef> evidence = new ArrayList<>();
        for (JsonNode value : array(required(object, "evidence"))) {
            evidence.add(evidenceRefFrom(value));
        }
        return new SemanticObservation(enumField(object, "code", ObservationCode.class), text(object, "description"),
                candidates, evidence, text(object, "provenance"));
    }

    private ObjectNode attemptNode(RunAttempt attempt) {
        ObjectNode node = object();
        node.put("attempt_id", attempt.attemptId().value());
        node.set("revision_vector", revisionsNode(attempt.revisionVector()));
        node.set("issued_capabilities", capabilitiesNode(attempt.issuedCapabilities()));
        node.set("issued_candidates", candidatesNode(attempt.issuedCandidates()));
        node.set("issued_evidence", evidenceNode(attempt.issuedEvidence()));
        node.set("observations", observationsNode(attempt.observations()));
        return node;
    }

    private RunAttempt attemptFrom(JsonNode node) {
        ObjectNode object = object(node);
        requireExactFields(object, "attempt_id", "revision_vector", "issued_capabilities", "issued_candidates", "issued_evidence", "observations");
        return new RunAttempt(attemptId(object, "attempt_id"), revisionsFrom(required(object, "revision_vector")),
                capabilitiesFrom(required(object, "issued_capabilities")), candidatesFrom(required(object, "issued_candidates")),
                evidenceFrom(required(object, "issued_evidence")), observationsFrom(required(object, "observations")));
    }

    private ObjectNode budgetNode(AttemptBudget budget) {
        ObjectNode node = object();
        node.put("max_agent_steps", budget.maxAgentSteps()); node.put("used_agent_steps", budget.usedAgentSteps());
        node.put("max_semantic_queries", budget.maxSemanticQueries()); node.put("used_semantic_queries", budget.usedSemanticQueries());
        node.put("max_action_rejections", budget.maxActionRejections()); node.put("used_action_rejections", budget.usedActionRejections());
        node.put("max_revision_restarts", budget.maxRevisionRestarts()); node.put("used_revision_restarts", budget.usedRevisionRestarts());
        node.put("final_answer_reserve", budget.finalAnswerReserve()); node.put("used_final_answers", budget.usedFinalAnswers());
        return node;
    }

    private AttemptBudget budgetFrom(JsonNode node) {
        ObjectNode object = object(node);
        requireExactFields(object, "max_agent_steps", "used_agent_steps", "max_semantic_queries", "used_semantic_queries", "max_action_rejections", "used_action_rejections", "max_revision_restarts", "used_revision_restarts", "final_answer_reserve", "used_final_answers");
        return new AttemptBudget(intField(object, "max_agent_steps"), intField(object, "used_agent_steps"),
                intField(object, "max_semantic_queries"), intField(object, "used_semantic_queries"),
                intField(object, "max_action_rejections"), intField(object, "used_action_rejections"),
                intField(object, "max_revision_restarts"), intField(object, "used_revision_restarts"),
                intField(object, "final_answer_reserve"), intField(object, "used_final_answers"));
    }

    private ObjectNode requestIdentityNode(RunRequestIdentity identity) {
        ObjectNode node = object();
        node.put("session_id", identity.sessionIdValue()); node.put("exact_question", identity.exactQuestion());
        return node;
    }

    private RunRequestIdentity requestIdentityFrom(JsonNode node) {
        ObjectNode object = object(node);
        requireExactFields(object, "session_id", "exact_question");
        return new RunRequestIdentity(text(object, "session_id"), text(object, "exact_question"));
    }

    private ObjectNode revisionsNode(RevisionVector revisions) {
        ObjectNode node = object();
        ArrayNode entries = array();
        for (RepositoryId repositoryId : revisions.repositoryIds()) {
            ObjectNode entry = object();
            entry.put("repository_id", repositoryId.value());
            entry.put("revision", revisions.revisionOf(repositoryId).orElseThrow().value());
            entries.add(entry);
        }
        node.set("entries", entries);
        return node;
    }

    private RevisionVector revisionsFrom(JsonNode node) {
        ObjectNode object = object(node);
        requireExactFields(object, "entries");
        RevisionVector revisions = RevisionVector.empty();
        for (JsonNode entryNode : array(required(object, "entries"))) {
            ObjectNode entry = object(entryNode);
            requireExactFields(entry, "repository_id", "revision");
            revisions = revisions.pin(new RepositoryId(text(entry, "repository_id")), new RepositoryRevision(text(entry, "revision")));
        }
        return revisions;
    }

    private ArrayNode capabilitiesNode(Map<CapabilityHandle, CapabilityDescriptor> capabilities) {
        ArrayNode entries = array();
        for (Map.Entry<CapabilityHandle, CapabilityDescriptor> entry : capabilities.entrySet()) {
            ObjectNode value = object(); value.set("handle", capabilityHandleNode(entry.getKey())); value.set("descriptor", capabilityNode(entry.getValue()));
            entries.add(value);
        }
        return entries;
    }

    private Map<CapabilityHandle, CapabilityDescriptor> capabilitiesFrom(JsonNode node) {
        LinkedHashMap<CapabilityHandle, CapabilityDescriptor> values = new LinkedHashMap<>();
        for (JsonNode entryNode : array(node)) {
            ObjectNode entry = object(entryNode);
            requireExactFields(entry, "handle", "descriptor");
            CapabilityHandle handle = capabilityHandleFrom(required(entry, "handle"));
            putUnique(values, handle, capabilityFrom(required(entry, "descriptor")));
        }
        return values;
    }

    private ObjectNode capabilityNode(CapabilityDescriptor capability) {
        ObjectNode node = object(); node.put("name", capability.name()); node.put("version", capability.version());
        ArrayNode kinds = array(); for (CandidateKind kind : capability.acceptedCandidateKinds()) { kinds.add(kind.name()); }
        node.set("accepted_candidate_kinds", kinds); node.put("minimum_candidates", capability.minimumCandidates());
        node.put("maximum_candidates", capability.maximumCandidates()); node.set("query_schema", querySchemaNode(capability.querySchema()));
        return node;
    }

    private CapabilityDescriptor capabilityFrom(JsonNode node) {
        ObjectNode object = object(node); LinkedHashSet<CandidateKind> kinds = new LinkedHashSet<>();
        requireExactFields(object, "name", "version", "accepted_candidate_kinds", "minimum_candidates", "maximum_candidates", "query_schema");
        for (JsonNode kind : array(required(object, "accepted_candidate_kinds"))) {
            addUnique(kinds, enumText(kind, CandidateKind.class));
        }
        return new CapabilityDescriptor(text(object, "name"), text(object, "version"), kinds, intField(object, "minimum_candidates"),
                intField(object, "maximum_candidates"), querySchemaFrom(required(object, "query_schema")));
    }

    private ObjectNode querySchemaNode(CapabilityQuerySchema schema) {
        ObjectNode node = object(); ArrayNode arguments = array();
        for (ArgumentDefinition definition : schema.arguments()) { arguments.add(argumentNode(definition)); }
        node.set("arguments", arguments); return node;
    }

    private CapabilityQuerySchema querySchemaFrom(JsonNode node) {
        ObjectNode object = object(node); List<ArgumentDefinition> arguments = new ArrayList<>();
        requireExactFields(object, "arguments");
        for (JsonNode argument : array(required(object, "arguments"))) { arguments.add(argumentFrom(argument)); }
        return new CapabilityQuerySchema(arguments);
    }

    private ObjectNode argumentNode(ArgumentDefinition definition) {
        ObjectNode node = object(); node.put("name", definition.name()); node.put("type", definition.type().name()); node.put("required", definition.required());
        putOptionalInteger(node, "minimum", Optional.ofNullable(definition.minimum())); putOptionalInteger(node, "maximum", Optional.ofNullable(definition.maximum()));
        ArrayNode values = array(); for (String value : definition.enumValues()) { values.add(value); } node.set("enum_values", values); return node;
    }

    private ArgumentDefinition argumentFrom(JsonNode node) {
        ObjectNode object = object(node); LinkedHashSet<String> values = new LinkedHashSet<>();
        requireExactFields(object, "name", "type", "required", "minimum", "maximum", "enum_values");
        for (JsonNode value : array(required(object, "enum_values"))) {
            addUnique(values, text(value));
        }
        return new ArgumentDefinition(text(object, "name"), enumField(object, "type", ArgumentType.class), booleanField(object, "required"),
                optionalInt(object, "minimum").orElse(null), optionalInt(object, "maximum").orElse(null), values);
    }

    private ArrayNode candidatesNode(Map<CandidateHandle, IssuedCandidate> candidates) {
        ArrayNode entries = array();
        for (Map.Entry<CandidateHandle, IssuedCandidate> entry : candidates.entrySet()) {
            ObjectNode value = object();
            value.set("handle", candidateHandleNode(entry.getKey()));
            value.set("issued_handle", candidateHandleNode(entry.getValue().handle()));
            value.set("candidate", candidateNode(entry.getValue().candidate()));
            entries.add(value);
        }
        return entries;
    }

    private Map<CandidateHandle, IssuedCandidate> candidatesFrom(JsonNode node) {
        LinkedHashMap<CandidateHandle, IssuedCandidate> values = new LinkedHashMap<>();
        for (JsonNode entryNode : array(node)) {
            ObjectNode entry = object(entryNode);
            requireExactFields(entry, "handle", "issued_handle", "candidate");
            CandidateHandle handle = candidateHandleFrom(required(entry, "handle"));
            CandidateHandle issuedHandle = candidateHandleFrom(required(entry, "issued_handle"));
            putUnique(values, handle, new IssuedCandidate(issuedHandle, candidateFrom(required(entry, "candidate"))));
        }
        return values;
    }

    private ObjectNode candidateNode(AnalysisCandidate candidate) {
        ObjectNode node = object(); node.put("candidate_type", candidate.kind().name()); node.put("repository_id", candidate.repositoryId().value()); node.put("description", candidate.description());
        switch (candidate) {
            case RepositoryCandidate ignored -> { }
            case RouteCandidate value -> { node.put("analyzed_revision", value.analyzedRevision().value()); node.put("route", value.route()); }
            case SemanticTargetCandidate value -> { node.put("analyzed_revision", value.analyzedRevision().value()); node.set("semantic_target", semanticTargetNode(value.semanticTarget())); }
        }
        return node;
    }

    private AnalysisCandidate candidateFrom(JsonNode node) {
        ObjectNode object = object(node); String type = text(object, "candidate_type"); RepositoryId repositoryId = new RepositoryId(text(object, "repository_id")); String description = text(object, "description");
        return switch (type) {
            case "REPOSITORY" -> { requireExactFields(object, "candidate_type", "repository_id", "description"); yield new RepositoryCandidate(repositoryId, description); }
            case "ROUTE" -> { requireExactFields(object, "candidate_type", "repository_id", "description", "analyzed_revision", "route"); yield new RouteCandidate(repositoryId, new RepositoryRevision(text(object, "analyzed_revision")), text(object, "route"), description); }
            case "SEMANTIC_TARGET" -> { requireExactFields(object, "candidate_type", "repository_id", "description", "analyzed_revision", "semantic_target"); yield new SemanticTargetCandidate(repositoryId, new RepositoryRevision(text(object, "analyzed_revision")), semanticTargetFrom(required(object, "semantic_target")), description); }
            default -> throw new PersistenceDocumentException("unsupported candidate document discriminator");
        };
    }

    private ArrayNode evidenceNode(Map<EvidenceHandle, IssuedEvidence> evidence) {
        ArrayNode entries = array();
        for (Map.Entry<EvidenceHandle, IssuedEvidence> entry : evidence.entrySet()) {
            ObjectNode value = object();
            value.set("handle", evidenceHandleNode(entry.getKey()));
            value.set("issued_handle", evidenceHandleNode(entry.getValue().handle()));
            value.set("evidence", evidenceRefNode(entry.getValue().evidence()));
            entries.add(value);
        }
        return entries;
    }

    private Map<EvidenceHandle, IssuedEvidence> evidenceFrom(JsonNode node) {
        LinkedHashMap<EvidenceHandle, IssuedEvidence> values = new LinkedHashMap<>();
        for (JsonNode entryNode : array(node)) {
            ObjectNode entry = object(entryNode);
            requireExactFields(entry, "handle", "issued_handle", "evidence");
            EvidenceHandle handle = evidenceHandleFrom(required(entry, "handle"));
            EvidenceHandle issuedHandle = evidenceHandleFrom(required(entry, "issued_handle"));
            putUnique(values, handle, new IssuedEvidence(issuedHandle, evidenceRefFrom(required(entry, "evidence"))));
        }
        return values;
    }

    private ObjectNode evidenceRefNode(EvidenceRef evidence) {
        ObjectNode node = object(); node.put("evidence_type", "EVIDENCE_REF"); node.put("source_service", evidence.sourceService());
        node.put("repository_id", evidence.repositoryId().value()); node.put("repository_revision", evidence.repositoryRevision().value());
        node.set("semantic_target", semanticTargetNode(evidence.semanticTarget())); node.put("content", evidence.content());
        ArrayNode warnings = array(); for (EvidenceWarning warning : evidence.warnings()) { warnings.add(evidenceWarningNode(warning)); }
        node.set("warnings", warnings); node.put("artifact_digest", evidence.artifactRef().digest()); return node;
    }

    private EvidenceRef evidenceRefFrom(JsonNode node) {
        ObjectNode object = object(node); requireDiscriminator(object, "evidence_type", "EVIDENCE_REF", "unsupported evidence document discriminator");
        requireExactFields(object, "evidence_type", "source_service", "repository_id", "repository_revision", "semantic_target", "content", "warnings", "artifact_digest");
        List<EvidenceWarning> warnings = new ArrayList<>();
        for (JsonNode warning : array(required(object, "warnings"))) { warnings.add(evidenceWarningFrom(warning)); }
        return new EvidenceRef(text(object, "source_service"), new RepositoryId(text(object, "repository_id")),
                new RepositoryRevision(text(object, "repository_revision")), semanticTargetFrom(required(object, "semantic_target")),
                text(object, "content"), warnings, new ArtifactRef(text(object, "artifact_digest")));
    }

    private ObjectNode evidenceWarningNode(EvidenceWarning warning) {
        ObjectNode node = object(); node.put("code", warning.code()); node.put("message", warning.message()); return node;
    }

    private EvidenceWarning evidenceWarningFrom(JsonNode node) {
        ObjectNode object = object(node); requireExactFields(object, "code", "message"); return new EvidenceWarning(text(object, "code"), text(object, "message"));
    }

    private ObjectNode semanticTargetNode(SemanticTarget target) {
        ObjectNode node = object(); node.put("kind", target.kind().name()); node.put("key", target.key());
        putOptionalNode(node, "source_range", target.sourceRange().map(this::sourceRangeNode)); return node;
    }

    private SemanticTarget semanticTargetFrom(JsonNode node) {
        ObjectNode object = object(node); requireExactFields(object, "kind", "key", "source_range"); return new SemanticTarget(enumField(object, "kind", SemanticTargetKind.class), text(object, "key"),
                optionalNode(object, "source_range").map(this::sourceRangeFrom));
    }

    private ObjectNode sourceRangeNode(SourceRange range) {
        ObjectNode node = object(); node.put("source_path", range.sourcePath()); node.put("start_line", range.startLine()); node.put("start_column", range.startColumn());
        node.put("end_line", range.endLine()); node.put("end_column", range.endColumn()); return node;
    }

    private SourceRange sourceRangeFrom(JsonNode node) {
        ObjectNode object = object(node); requireExactFields(object, "source_path", "start_line", "start_column", "end_line", "end_column"); return new SourceRange(text(object, "source_path"), intField(object, "start_line"),
                intField(object, "start_column"), intField(object, "end_line"), intField(object, "end_column"));
    }

    private ArrayNode observationsNode(Map<ObservationId, AgentObservation> observations) {
        ArrayNode entries = array();
        for (Map.Entry<ObservationId, AgentObservation> entry : observations.entrySet()) {
            ObjectNode value = object(); value.put("observation_id", entry.getKey().value()); value.set("observation", observationNode(entry.getValue())); entries.add(value);
        }
        return entries;
    }

    private Map<ObservationId, AgentObservation> observationsFrom(JsonNode node) {
        LinkedHashMap<ObservationId, AgentObservation> values = new LinkedHashMap<>();
        for (JsonNode entryNode : array(node)) {
            ObjectNode entry = object(entryNode); ObservationId id = new ObservationId(text(entry, "observation_id"));
            requireExactFields(entry, "observation_id", "observation");
            AgentObservation observation = observationFrom(required(entry, "observation"));
            if (!id.equals(observation.id())) { throw new PersistenceDocumentException("observation map key does not match observation payload"); }
            putUnique(values, id, observation);
        }
        return values;
    }

    private ObjectNode observationNode(AgentObservation observation) {
        ObjectNode node = object(); node.put("observation_type", "AGENT_OBSERVATION"); node.put("id", observation.id().value());
        node.put("source", observation.source().name()); node.put("code", observation.code().name()); node.put("description", observation.description());
        ArrayNode candidates = array(); for (CandidateHandle handle : observation.candidateHandles()) { candidates.add(candidateHandleNode(handle)); } node.set("candidate_handles", candidates);
        ArrayNode evidence = array(); for (EvidenceHandle handle : observation.evidenceHandles()) { evidence.add(evidenceHandleNode(handle)); } node.set("evidence_handles", evidence);
        node.put("provenance", observation.provenance()); return node;
    }

    private AgentObservation observationFrom(JsonNode node) {
        ObjectNode object = object(node); requireDiscriminator(object, "observation_type", "AGENT_OBSERVATION", "unsupported observation document discriminator");
        requireExactFields(object, "observation_type", "id", "source", "code", "description", "candidate_handles", "evidence_handles", "provenance");
        LinkedHashSet<CandidateHandle> candidates = new LinkedHashSet<>();
        for (JsonNode handle : array(required(object, "candidate_handles"))) {
            addUnique(candidates, candidateHandleFrom(handle));
        }
        LinkedHashSet<EvidenceHandle> evidence = new LinkedHashSet<>();
        for (JsonNode handle : array(required(object, "evidence_handles"))) {
            addUnique(evidence, evidenceHandleFrom(handle));
        }
        return new AgentObservation(new ObservationId(text(object, "id")), enumField(object, "source", ObservationSource.class),
                enumField(object, "code", ObservationCode.class), text(object, "description"), candidates, evidence, text(object, "provenance"));
    }

    private ObjectNode actionNode(AgentAction action) {
        ObjectNode node = object();
        switch (action) {
            case QueryAction value -> {
                node.put("action_type", "QUERY"); node.set("capability", capabilityHandleNode(value.capability()));
                ArrayNode candidates = array(); for (CandidateHandle candidate : value.candidates()) { candidates.add(candidateHandleNode(candidate)); } node.set("candidates", candidates);
                node.put("question_to_resolve", value.questionToResolve()); ObjectNode arguments = object();
                for (Map.Entry<String, String> argument : value.arguments().entrySet()) { arguments.put(argument.getKey(), argument.getValue()); }
                node.set("arguments", arguments); node.put("rationale", value.rationale());
            }
            case AnswerAction value -> { node.put("action_type", "ANSWER"); node.set("document", answerDocumentNode(value.document())); }
            case ClarifyAction value -> {
                node.put("action_type", "CLARIFY"); node.put("question", value.question());
                ArrayNode candidates = array(); for (CandidateHandle candidate : value.candidates()) { candidates.add(candidateHandleNode(candidate)); } node.set("candidates", candidates); node.put("reason", value.reason());
            }
        }
        return node;
    }

    private AgentAction actionFrom(JsonNode node) {
        ObjectNode object = object(node);
        return switch (text(object, "action_type")) {
            case "QUERY" -> {
                requireExactFields(
                        object,
                        "action_type",
                        "capability",
                        "candidates",
                        "question_to_resolve",
                        "arguments",
                        "rationale");
                yield queryActionFrom(object);
            }
            case "ANSWER" -> {
                requireExactFields(object, "action_type", "document");
                yield new AnswerAction(answerDocumentFrom(required(object, "document")));
            }
            case "CLARIFY" -> {
                yield clarifyActionFrom(object);
            }
            default -> throw new PersistenceDocumentException("unsupported action document discriminator");
        };
    }

    private QueryAction queryActionFrom(ObjectNode node) {
        List<CandidateHandle> candidates = candidateHandleList(required(node, "candidates")); LinkedHashMap<String, String> arguments = new LinkedHashMap<>();
        ObjectNode argumentNode = object(required(node, "arguments")); argumentNode.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) { throw new PersistenceDocumentException("invalid query action argument value"); }
            arguments.put(entry.getKey(), entry.getValue().textValue());
        });
        return new QueryAction(capabilityHandleFrom(required(node, "capability")), candidates, text(node, "question_to_resolve"), arguments, text(node, "rationale"));
    }

    private ClarifyAction clarifyActionFrom(JsonNode node) {
        ObjectNode object = object(node);
        requireExactFields(object, "action_type", "question", "candidates", "reason");
        requireDiscriminator(
                object,
                "action_type",
                "CLARIFY",
                "unsupported clarification action document discriminator");
        return new ClarifyAction(
                text(object, "question"),
                candidateHandleList(required(object, "candidates")),
                text(object, "reason"));
    }

    private ObjectNode answerDocumentNode(AnswerDocument document) {
        ObjectNode node = object(); ArrayNode statements = array(); for (AnswerStatement statement : document.statements()) { statements.add(answerStatementNode(statement)); }
        node.set("statements", statements); return node;
    }

    private AnswerDocument answerDocumentFrom(JsonNode node) {
        ObjectNode object = object(node); List<AnswerStatement> statements = new ArrayList<>();
        requireExactFields(object, "statements");
        for (JsonNode statement : array(required(object, "statements"))) { statements.add(answerStatementFrom(statement)); }
        return new AnswerDocument(statements);
    }

    private ObjectNode answerStatementNode(AnswerStatement statement) {
        ObjectNode node = object(); node.put("statement_id", statement.statementId().value()); node.put("type", statement.type().name()); node.put("text", statement.text());
        putOptionalNode(node, "claim_id", statement.claimId().map(value -> object().put("value", value.value())));
        ArrayNode citations = array(); for (EvidenceHandle citation : statement.citations()) { citations.add(evidenceHandleNode(citation)); } node.set("citations", citations);
        ArrayNode observations = array(); for (ObservationId id : statement.observationIds()) { observations.add(id.value()); } node.set("observation_ids", observations); return node;
    }

    private AnswerStatement answerStatementFrom(JsonNode node) {
        ObjectNode object = object(node); LinkedHashSet<EvidenceHandle> citations = new LinkedHashSet<>();
        requireExactFields(object, "statement_id", "type", "text", "claim_id", "citations", "observation_ids");
        for (JsonNode citation : array(required(object, "citations"))) {
            addUnique(citations, evidenceHandleFrom(citation));
        }
        LinkedHashSet<ObservationId> observations = new LinkedHashSet<>();
        for (JsonNode id : array(required(object, "observation_ids"))) {
            addUnique(observations, new ObservationId(text(id)));
        }
        Optional<ClaimId> claimId = optionalNode(object, "claim_id").map(value -> {
            ObjectNode claim = object(value);
            requireExactFields(claim, "value");
            return new ClaimId(text(claim, "value"));
        });
        return new AnswerStatement(new StatementId(text(object, "statement_id")), enumField(object, "type", StatementType.class), text(object, "text"), claimId, citations, observations);
    }

    private ObjectNode answerVerdictNode(AnswerVerdict verdict) {
        ObjectNode node = object(); node.put("disposition", verdict.disposition().name()); ArrayNode statementVerdicts = array();
        for (StatementVerdict statementVerdict : verdict.statementVerdicts()) { statementVerdicts.add(statementVerdictNode(statementVerdict)); } node.set("statement_verdicts", statementVerdicts);
        node.set("unaddressed_parts", stringArray(verdict.unaddressedParts())); node.set("blocking_uncertainties", stringArray(verdict.blockingUncertainties())); node.set("rejection_reasons", stringArray(verdict.rejectionReasons())); return node;
    }

    private AnswerVerdict answerVerdictFrom(JsonNode node) {
        ObjectNode object = object(node); List<StatementVerdict> statements = new ArrayList<>();
        requireExactFields(object, "disposition", "statement_verdicts", "unaddressed_parts", "blocking_uncertainties", "rejection_reasons");
        for (JsonNode statement : array(required(object, "statement_verdicts"))) { statements.add(statementVerdictFrom(statement)); }
        return new AnswerVerdict(enumField(object, "disposition", AnswerDisposition.class), statements, stringList(required(object, "unaddressed_parts")),
                stringList(required(object, "blocking_uncertainties")), stringList(required(object, "rejection_reasons")));
    }

    private ObjectNode statementVerdictNode(StatementVerdict verdict) {
        ObjectNode node = object(); node.put("statement_id", verdict.statementId().value()); node.put("status", verdict.status().name()); node.put("description", verdict.description()); return node;
    }

    private StatementVerdict statementVerdictFrom(JsonNode node) {
        ObjectNode object = object(node); requireExactFields(object, "statement_id", "status", "description"); return new StatementVerdict(new StatementId(text(object, "statement_id")), enumField(object, "status", StatementVerdictStatus.class), text(object, "description"));
    }

    private ObjectNode conversationTurnNode(ConversationTurn turn) {
        ObjectNode node = object(); node.put("run_id", turn.runId().value()); node.put("user_message", turn.userMessage()); node.put("assistant_message", turn.assistantMessage()); node.put("turn_type", turn.type().name()); return node;
    }

    private ConversationTurn conversationTurnFrom(JsonNode node) {
        ObjectNode object = object(node); requireExactFields(object, "run_id", "user_message", "assistant_message", "turn_type"); return new ConversationTurn(runId(object, "run_id"), text(object, "user_message"), text(object, "assistant_message"), enumField(object, "turn_type", ConversationTurnType.class));
    }

    private ObjectNode pendingNode(PendingTerminalResponse pending) {
        ObjectNode node = object();
        switch (pending) {
            case PendingTerminalResponse.Answer value -> {
                node.put("pending_type", "ANSWER"); node.put("session_id", value.sessionId().value()); node.set("turn", conversationTurnNode(value.turn()));
                node.set("document", answerDocumentNode(value.document())); node.set("verdict", answerVerdictNode(value.verdict())); node.put("expected_outcome", value.expectedOutcome().name());
            }
            case PendingTerminalResponse.Clarification value -> {
                node.put("pending_type", "CLARIFICATION"); node.put("session_id", value.sessionId().value()); node.set("turn", conversationTurnNode(value.turn())); node.set("action", actionNode(value.action()));
            }
        }
        return node;
    }

    private PendingTerminalResponse pendingFrom(JsonNode node) {
        ObjectNode object = object(node);
        return switch (text(object, "pending_type")) {
            case "ANSWER" -> {
                requireExactFields(
                        object,
                        "pending_type",
                        "session_id",
                        "turn",
                        "document",
                        "verdict",
                        "expected_outcome");
                yield new PendingTerminalResponse.Answer(
                        new SessionId(text(object, "session_id")),
                        conversationTurnFrom(required(object, "turn")),
                        answerDocumentFrom(required(object, "document")),
                        answerVerdictFrom(required(object, "verdict")),
                        enumField(object, "expected_outcome", RunOutcome.class));
            }
            case "CLARIFICATION" -> {
                requireExactFields(object, "pending_type", "session_id", "turn", "action");
                yield new PendingTerminalResponse.Clarification(
                        new SessionId(text(object, "session_id")),
                        conversationTurnFrom(required(object, "turn")),
                        clarifyActionFrom(required(object, "action")));
            }
            default -> throw new PersistenceDocumentException("unsupported pending terminal response discriminator");
        };
    }

    private ObjectNode capabilityHandleNode(CapabilityHandle handle) { ObjectNode node = object(); node.put("value", handle.value()); node.set("binding", bindingNode(handle.binding())); return node; }
    private CapabilityHandle capabilityHandleFrom(JsonNode node) { ObjectNode object = object(node); requireExactFields(object, "value", "binding"); return new CapabilityHandle(text(object, "value"), bindingFrom(required(object, "binding"))); }
    private ObjectNode candidateHandleNode(CandidateHandle handle) { ObjectNode node = object(); node.put("value", handle.value()); node.set("binding", bindingNode(handle.binding())); node.put("kind", handle.kind().name()); return node; }
    private CandidateHandle candidateHandleFrom(JsonNode node) { ObjectNode object = object(node); requireExactFields(object, "value", "binding", "kind"); return new CandidateHandle(text(object, "value"), bindingFrom(required(object, "binding")), enumField(object, "kind", CandidateKind.class)); }
    private ObjectNode evidenceHandleNode(EvidenceHandle handle) { ObjectNode node = object(); node.put("value", handle.value()); node.set("binding", bindingNode(handle.binding())); return node; }
    private EvidenceHandle evidenceHandleFrom(JsonNode node) { ObjectNode object = object(node); requireExactFields(object, "value", "binding"); return new EvidenceHandle(text(object, "value"), bindingFrom(required(object, "binding"))); }
    private ObjectNode bindingNode(HandleBinding binding) { ObjectNode node = object(); node.put("run_id", binding.runId().value()); node.put("attempt_id", binding.attemptId().value()); node.set("revision_vector", revisionsNode(binding.revisionVector())); return node; }
    private HandleBinding bindingFrom(JsonNode node) { ObjectNode object = object(node); requireExactFields(object, "run_id", "attempt_id", "revision_vector"); return new HandleBinding(runId(object, "run_id"), attemptId(object, "attempt_id"), revisionsFrom(required(object, "revision_vector"))); }
    private List<CandidateHandle> candidateHandleList(JsonNode node) { List<CandidateHandle> handles = new ArrayList<>(); for (JsonNode handle : array(node)) { handles.add(candidateHandleFrom(handle)); } return handles; }

    private ObjectNode object() { return objectMapper.createObjectNode(); }
    private ArrayNode array() { return objectMapper.createArrayNode(); }
    private ObjectNode object(JsonNode node) { if (!node.isObject()) { throw new PersistenceDocumentException("invalid document object shape"); } return (ObjectNode) node; }
    private void requireExactFields(ObjectNode node, String... allowedFields) {
        Set<String> allowed = Set.of(allowedFields);
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw new PersistenceDocumentException("unsupported document field");
            }
        }
    }
    private ArrayNode array(JsonNode node) { if (!node.isArray()) { throw new PersistenceDocumentException("invalid document array shape"); } return (ArrayNode) node; }
    private JsonNode required(ObjectNode node, String field) { JsonNode value = node.path(field); if (value.isMissingNode() || value.isNull()) { throw new PersistenceDocumentException("missing required document field"); } return value; }
    private Optional<JsonNode> optionalNode(ObjectNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) {
            throw new PersistenceDocumentException("missing nullable document field");
        }
        return value.isNull() ? Optional.empty() : Optional.of(value);
    }
    private String text(ObjectNode node, String field) { return text(required(node, field)); }
    private String text(JsonNode node) { if (!node.isTextual()) { throw new PersistenceDocumentException("invalid document text field"); } return node.textValue(); }
    private long longField(ObjectNode node, String field) { JsonNode value = required(node, field); if (!value.canConvertToLong() || !value.isIntegralNumber()) { throw new PersistenceDocumentException("invalid document integer field"); } return value.longValue(); }
    private int intField(ObjectNode node, String field) { JsonNode value = required(node, field); if (!value.canConvertToInt() || !value.isIntegralNumber()) { throw new PersistenceDocumentException("invalid document integer field"); } return value.intValue(); }
    private boolean booleanField(ObjectNode node, String field) { JsonNode value = required(node, field); if (!value.isBoolean()) { throw new PersistenceDocumentException("invalid document boolean field"); } return value.booleanValue(); }
    private <T extends Enum<T>> T enumField(ObjectNode node, String field, Class<T> type) { return enumText(required(node, field), type); }
    private <T extends Enum<T>> T enumText(JsonNode node, Class<T> type) { try { return Enum.valueOf(type, text(node)); } catch (IllegalArgumentException exception) { throw new PersistenceDocumentException("unsupported document enum value"); } }
    private <T extends Enum<T>> Optional<T> optionalEnum(ObjectNode node, String field, Class<T> type) { return optionalNode(node, field).map(value -> enumText(value, type)); }
    private <T extends Enum<T>> void putOptionalEnum(ObjectNode node, String field, Optional<T> value) { if (value.isPresent()) { node.put(field, value.orElseThrow().name()); } else { node.putNull(field); } }
    private void putOptionalInteger(ObjectNode node, String field, Optional<Integer> value) { if (value.isPresent()) { node.put(field, value.orElseThrow()); } else { node.putNull(field); } }
    private void putOptionalNode(ObjectNode node, String field, Optional<? extends JsonNode> value) { if (value.isPresent()) { node.set(field, value.orElseThrow()); } else { node.putNull(field); } }
    private Optional<Integer> optionalInt(ObjectNode node, String field) { return optionalNode(node, field).map(value -> { if (!value.canConvertToInt() || !value.isIntegralNumber()) { throw new PersistenceDocumentException("invalid document integer field"); } return value.intValue(); }); }
    private AnalysisRunId runId(ObjectNode node, String field) { return new AnalysisRunId(text(node, field)); }
    private AnalysisAttemptId attemptId(ObjectNode node, String field) { return new AnalysisAttemptId(text(node, field)); }
    private void requireDiscriminator(ObjectNode node, String field, String expected, String message) { if (!expected.equals(text(node, field))) { throw new PersistenceDocumentException(message); } }
    private ArrayNode stringArray(List<String> values) { ArrayNode node = array(); for (String value : values) { node.add(value); } return node; }
    private List<String> stringList(JsonNode node) { List<String> values = new ArrayList<>(); for (JsonNode value : array(node)) { values.add(text(value)); } return values; }
    private <K, V> void putUnique(Map<K, V> values, K key, V value) { if (Objects.nonNull(values.putIfAbsent(key, value))) { throw new PersistenceDocumentException("duplicate document map key"); } }
    private <T> void addUnique(Set<T> values, T value) {
        if (!values.add(value)) {
            throw new PersistenceDocumentException("duplicate persisted set entry");
        }
    }
}
