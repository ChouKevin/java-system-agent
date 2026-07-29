package com.java.system.agent.answering.domain.run;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.answer.AnswerAcceptance;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent Run append-only lifecycle 可以接受的事件
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "event_type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AgentEvent.RunStarted.class, name = "RUN_STARTED"),
        @JsonSubTypes.Type(value = AgentEvent.AttemptStarted.class, name = "ATTEMPT_STARTED"),
        @JsonSubTypes.Type(value = AgentEvent.ContextIssued.class, name = "CONTEXT_ISSUED"),
        @JsonSubTypes.Type(value = AgentEvent.ActionAccepted.class, name = "ACTION_ACCEPTED"),
        @JsonSubTypes.Type(value = AgentEvent.ActionRejected.class, name = "ACTION_REJECTED"),
        @JsonSubTypes.Type(value = AgentEvent.QueryBudgetConsumed.class, name = "QUERY_BUDGET_CONSUMED"),
        @JsonSubTypes.Type(value = AgentEvent.ObservationRecorded.class, name = "OBSERVATION_RECORDED"),
        @JsonSubTypes.Type(value = AgentEvent.AttemptInvalidated.class, name = "ATTEMPT_INVALIDATED"),
        @JsonSubTypes.Type(value = AgentEvent.AnswerProposed.class, name = "ANSWER_PROPOSED"),
        @JsonSubTypes.Type(value = AgentEvent.AnswerAccepted.class, name = "ANSWER_ACCEPTED"),
        @JsonSubTypes.Type(value = AgentEvent.AnswerRejected.class, name = "ANSWER_REJECTED"),
        @JsonSubTypes.Type(
                value = AgentEvent.AnswerVerificationAbandoned.class,
                name = "ANSWER_VERIFICATION_ABANDONED"),
        @JsonSubTypes.Type(value = AgentEvent.ClarificationAccepted.class, name = "CLARIFICATION_ACCEPTED"),
        @JsonSubTypes.Type(value = AgentEvent.RunConcluded.class, name = "RUN_CONCLUDED")
})
public sealed interface AgentEvent permits AgentEvent.RunStarted, AgentEvent.AttemptStarted,
        AgentEvent.ContextIssued, AgentEvent.ActionAccepted, AgentEvent.ActionRejected,
        AgentEvent.QueryBudgetConsumed, AgentEvent.ObservationRecorded, AgentEvent.AttemptInvalidated,
        AgentEvent.AnswerProposed, AgentEvent.AnswerAccepted, AgentEvent.AnswerRejected,
        AgentEvent.AnswerVerificationAbandoned, AgentEvent.ClarificationAccepted, AgentEvent.RunConcluded {

    AnalysisRunId runId();

    AnalysisAttemptId attemptId();

    long expectedStateRevision();

    record RunStarted(AnalysisRunId runId, AnalysisAttemptId attemptId,
                      long expectedStateRevision) implements AgentEvent {
        public RunStarted {
            validateEnvelope(runId, attemptId, expectedStateRevision);
        }
    }

    record AttemptStarted(AnalysisRunId runId, AnalysisAttemptId attemptId,
                          long expectedStateRevision, RunAttempt newAttempt) implements AgentEvent {
        public AttemptStarted {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(newAttempt, "new run attempt must not be null");
        }
    }

    record ContextIssued(AnalysisRunId runId, AnalysisAttemptId attemptId, long expectedStateRevision,
                         RevisionVector revisions,
                         Map<CapabilityHandle, CapabilityPolicy> capabilities,
                         Map<CandidateHandle, IssuedCandidate> candidates,
                         Map<EvidenceHandle, IssuedEvidence> evidence,
                         Map<ObservationId, AgentObservation> observations) implements AgentEvent {
        public ContextIssued {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(revisions, "issued context revisions must not be null");
            capabilities = immutableMap(capabilities, "issued capabilities");
            candidates = immutableMap(candidates, "issued candidates");
            evidence = immutableMap(evidence, "issued evidence");
            observations = immutableMap(observations, "issued observations");
        }
    }

    record ActionAccepted(AnalysisRunId runId, AnalysisAttemptId attemptId, long expectedStateRevision,
                          AgentAction action) implements AgentEvent {
        public ActionAccepted {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(action, "accepted agent action must not be null");
        }
    }

    record ActionRejected(AnalysisRunId runId, AnalysisAttemptId attemptId, long expectedStateRevision,
                          Optional<AgentAction> originalAction, String description) implements AgentEvent {
        public ActionRejected {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(originalAction, "rejected original action must not be null");
            Objects.requireNonNull(description, "action rejection description must not be null");
            if (description.isBlank()) {
                throw new IllegalArgumentException("action rejection description must not be blank");
            }
        }
    }

    record QueryBudgetConsumed(AnalysisRunId runId, AnalysisAttemptId attemptId,
                               long expectedStateRevision) implements AgentEvent {
        public QueryBudgetConsumed {
            validateEnvelope(runId, attemptId, expectedStateRevision);
        }
    }

    record ObservationRecorded(AnalysisRunId runId, AnalysisAttemptId attemptId,
                               long expectedStateRevision, AgentObservation observation) implements AgentEvent {
        public ObservationRecorded {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(observation, "recorded observation must not be null");
        }
    }

    record AttemptInvalidated(AnalysisRunId runId, AnalysisAttemptId attemptId,
                              long expectedStateRevision, String reason,
                              boolean consumeRevisionRestart) implements AgentEvent {
        public AttemptInvalidated {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(reason, "attempt invalidation reason must not be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("attempt invalidation reason must not be blank");
            }
        }
    }

    record AnswerProposed(AnalysisRunId runId, AnalysisAttemptId attemptId, long expectedStateRevision,
                          PendingAnswerVerification proposal) implements AgentEvent {
        public AnswerProposed {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(proposal, "pending answer verification proposal must not be null");
            if (!attemptId.equals(proposal.attemptId())) {
                throw new IllegalArgumentException("pending answer verification must belong to the event attempt");
            }
        }
    }

    record AnswerAccepted(AnalysisRunId runId, AnalysisAttemptId attemptId, long expectedStateRevision,
                          AnswerDocument document, AnswerAcceptance acceptance,
                          SessionId sessionId, ConversationTurn turn) implements AgentEvent {
        public AnswerAccepted {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(document, "accepted answer document must not be null");
            Objects.requireNonNull(acceptance, "accepted answer acceptance must not be null");
            Objects.requireNonNull(sessionId, "accepted answer session ID must not be null");
            Objects.requireNonNull(turn, "accepted answer conversation turn must not be null");
            if (!runId.equals(turn.runId()) || turn.type() != ConversationTurnType.ANSWER
                    || !turn.assistantMessage().equals(document.renderParagraphs())) {
                throw new IllegalArgumentException("accepted answer turn must render the document for the same run");
            }
        }
    }

    record AnswerRejected(AnalysisRunId runId, AnalysisAttemptId attemptId, long expectedStateRevision,
                          AnswerVerdict verdict) implements AgentEvent {
        public AnswerRejected {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(verdict, "rejected answer verdict must not be null");
            if (verdict.disposition() != AnswerDisposition.REJECTED) {
                throw new IllegalArgumentException("answer rejection requires a rejected verdict");
            }
        }
    }

    record AnswerVerificationAbandoned(AnalysisRunId runId, AnalysisAttemptId attemptId,
                                       long expectedStateRevision, AnswerVerificationAbandonReason reason)
            implements AgentEvent {
        public AnswerVerificationAbandoned {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(reason, "answer verification abandon reason must not be null");
        }
    }

    record ClarificationAccepted(AnalysisRunId runId, AnalysisAttemptId attemptId,
                                 long expectedStateRevision, ClarifyAction action,
                                 SessionId sessionId, ConversationTurn turn) implements AgentEvent {
        public ClarificationAccepted {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(action, "accepted clarification action must not be null");
            Objects.requireNonNull(sessionId, "accepted clarification session ID must not be null");
            Objects.requireNonNull(turn, "accepted clarification conversation turn must not be null");
            if (!runId.equals(turn.runId()) || turn.type() != ConversationTurnType.CLARIFICATION
                    || !turn.assistantMessage().equals(action.question())) {
                throw new IllegalArgumentException("accepted clarification turn must match the action for the same run");
            }
        }
    }

    record RunConcluded(AnalysisRunId runId, AnalysisAttemptId attemptId, long expectedStateRevision,
                        RunOutcome outcome, Optional<RuntimeNoticeReason> runtimeNoticeReason,
                        Optional<RunFailureReason> failureReason) implements AgentEvent {
        public RunConcluded {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(outcome, "run outcome must not be null");
            runtimeNoticeReason = Objects.requireNonNull(runtimeNoticeReason, "runtime notice reason must not be null");
            failureReason = Objects.requireNonNull(failureReason, "run failure reason must not be null");
            if (runtimeNoticeReason.isPresent() && outcome != RunOutcome.INCONCLUSIVE) {
                throw new IllegalArgumentException("runtime notice requires an inconclusive conclusion");
            }
            if (failureReason.isPresent() && outcome != RunOutcome.FAILED) {
                throw new IllegalArgumentException("failure reason requires a failed conclusion");
            }
        }
    }

    private static void validateEnvelope(AnalysisRunId runId, AnalysisAttemptId attemptId,
                                         long expectedStateRevision) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(attemptId, "analysis attempt ID must not be null");
        if (expectedStateRevision < 0) {
            throw new IllegalArgumentException("expected state revision must not be negative");
        }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values, String description) {
        Objects.requireNonNull(values, description + " must not be null");
        Map<K, V> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(Objects.requireNonNull(key, description + " key must not be null"),
                Objects.requireNonNull(value, description + " value must not be null")));
        return Collections.unmodifiableMap(copied);
    }
}
