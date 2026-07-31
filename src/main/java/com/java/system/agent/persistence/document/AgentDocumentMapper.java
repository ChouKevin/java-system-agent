package com.java.system.agent.persistence.document;

import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.persistence.document.AgentPersistenceDocuments.AttemptStartedDocument;
import com.java.system.agent.persistence.document.AgentPersistenceDocuments.ContextIssuedDocument;
import com.java.system.agent.persistence.document.AgentPersistenceDocuments.MapEntryDocument;
import com.java.system.agent.persistence.document.AgentPersistenceDocuments.RunAttemptDocument;
import com.java.system.agent.persistence.document.AgentPersistenceDocuments.StateDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * domain 與少量 persistence 特殊 collection 文件之間的明確映射
 */
final class AgentDocumentMapper {

    StateDocument stateDocument(AgentRunState state) {
        return new StateDocument(
                state.runId(),
                state.status(),
                attemptDocument(state.currentAttempt()),
                state.attemptSequence(),
                state.budget(),
                state.acceptedActionCount(),
                state.rejectedActionCount(),
                state.stateRevision(),
                state.finalOutcome(),
                state.runtimeNoticeReason(),
                state.failureReason(),
                state.pendingTerminalResponse(),
                state.pendingAnswerVerification(),
                state.requestIdentity());
    }

    AgentRunState state(StateDocument document) {
        return new AgentRunState(
                document.runId(),
                document.status(),
                attempt(document.currentAttempt()),
                document.attemptSequence(),
                document.budget(),
                document.acceptedActionCount(),
                document.rejectedActionCount(),
                document.stateRevision(),
                document.finalOutcome(),
                document.runtimeNoticeReason(),
                document.failureReason(),
                document.pendingTerminalResponse(),
                document.pendingAnswerVerification(),
                document.requestIdentity());
    }

    RunAttemptDocument attemptDocument(RunAttempt attempt) {
        return new RunAttemptDocument(
                attempt.attemptId(),
                attempt.revisionVector(),
                entries(attempt.issuedCapabilities()),
                entries(attempt.issuedCandidates()),
                entries(attempt.issuedEvidence()),
                entries(attempt.observations()));
    }

    RunAttempt attempt(RunAttemptDocument document) {
        return new RunAttempt(
                document.attemptId(),
                document.revisionVector(),
                map(document.issuedCapabilities(), "duplicate capability handle"),
                map(document.issuedCandidates(), "duplicate candidate handle"),
                map(document.issuedEvidence(), "duplicate evidence handle"),
                map(document.observations(), "duplicate observation ID"));
    }

    AttemptStartedDocument attemptStartedDocument(AgentEvent.AttemptStarted event) {
        return new AttemptStartedDocument(
                "ATTEMPT_STARTED",
                event.runId(),
                event.attemptId(),
                event.expectedStateRevision(),
                attemptDocument(event.newAttempt()));
    }

    AgentEvent.AttemptStarted attemptStarted(AttemptStartedDocument document) {
        return new AgentEvent.AttemptStarted(
                document.runId(),
                document.attemptId(),
                document.expectedStateRevision(),
                attempt(document.newAttempt()));
    }

    ContextIssuedDocument contextIssuedDocument(AgentEvent.ContextIssued event) {
        return new ContextIssuedDocument(
                "CONTEXT_ISSUED",
                event.runId(),
                event.attemptId(),
                event.expectedStateRevision(),
                event.revisions(),
                entries(event.capabilities()),
                entries(event.candidates()),
                entries(event.evidence()),
                entries(event.observations()));
    }

    AgentEvent.ContextIssued contextIssued(ContextIssuedDocument document) {
        Map<ObservationId, AgentObservation> observations = map(
                document.observations(), "duplicate observation ID");
        observations.forEach((observationId, observation) -> {
            if (!observationId.equals(observation.id())) {
                throw new PersistenceDocumentException("observation key must match observation ID");
            }
        });
        return new AgentEvent.ContextIssued(
                document.runId(),
                document.attemptId(),
                document.expectedStateRevision(),
                document.revisions(),
                map(document.capabilities(), "duplicate capability handle"),
                map(document.candidates(), "duplicate candidate handle"),
                map(document.evidence(), "duplicate evidence handle"),
                observations);
    }

    String eventType(AgentEvent event) {
        return switch (event) {
            case AgentEvent.RunStarted ignored -> "RUN_STARTED";
            case AgentEvent.AttemptStarted ignored -> "ATTEMPT_STARTED";
            case AgentEvent.ContextIssued ignored -> "CONTEXT_ISSUED";
            case AgentEvent.ActionAccepted ignored -> "ACTION_ACCEPTED";
            case AgentEvent.ActionRejected ignored -> "ACTION_REJECTED";
            case AgentEvent.QueryBudgetConsumed ignored -> "QUERY_BUDGET_CONSUMED";
            case AgentEvent.ExecuteBudgetConsumed ignored -> "EXECUTE_BUDGET_CONSUMED";
            case AgentEvent.ObservationRecorded ignored -> "OBSERVATION_RECORDED";
            case AgentEvent.AttemptInvalidated ignored -> "ATTEMPT_INVALIDATED";
            case AgentEvent.AnswerProposed ignored -> "ANSWER_PROPOSED";
            case AgentEvent.AnswerAccepted ignored -> "ANSWER_ACCEPTED";
            case AgentEvent.AnswerRejected ignored -> "ANSWER_REJECTED";
            case AgentEvent.AnswerVerificationAbandoned ignored -> "ANSWER_VERIFICATION_ABANDONED";
            case AgentEvent.ClarificationAccepted ignored -> "CLARIFICATION_ACCEPTED";
            case AgentEvent.RunConcluded ignored -> "RUN_CONCLUDED";
        };
    }

    private <K, V> List<MapEntryDocument<K, V>> entries(Map<K, V> values) {
        List<MapEntryDocument<K, V>> entries = new ArrayList<>();
        values.forEach((key, value) -> entries.add(new MapEntryDocument<>(key, value)));
        return List.copyOf(entries);
    }

    private <K, V> Map<K, V> map(List<MapEntryDocument<K, V>> entries, String duplicateMessage) {
        Map<K, V> values = new LinkedHashMap<>();
        for (MapEntryDocument<K, V> entry : entries) {
            if (values.containsKey(entry.key())) {
                throw new PersistenceDocumentException(duplicateMessage);
            }
            values.put(entry.key(), entry.value());
        }
        return values;
    }
}
