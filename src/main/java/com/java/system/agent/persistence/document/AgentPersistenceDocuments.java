package com.java.system.agent.persistence.document;

import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.IssuedCandidate;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.CapabilityHandle;
import com.java.system.agent.answering.domain.handle.CandidateHandle;
import com.java.system.agent.answering.domain.handle.EvidenceHandle;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.run.PendingAnswerVerification;
import com.java.system.agent.answering.domain.run.PendingTerminalResponse;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * persistence JSON 中只承接特殊 collection shape 與必要 envelope 的文件模型
 */
final class AgentPersistenceDocuments {

    private AgentPersistenceDocuments() {
    }

    /**
     * 含 required primitive 與特殊 attempt collection shape 的狀態文件
     */
    record StateDocument(
            AnalysisRunId runId,
            AgentRunStatus status,
            RunAttemptDocument currentAttempt,
            int attemptSequence,
            AttemptBudget budget,
            long acceptedActionCount,
            long rejectedActionCount,
            long stateRevision,
            Optional<RunOutcome> finalOutcome,
            Optional<RuntimeNoticeReason> runtimeNoticeReason,
            Optional<RunFailureReason> failureReason,
            Optional<PendingTerminalResponse> pendingTerminalResponse,
            Optional<PendingAnswerVerification> pendingAnswerVerification,
            RunRequestIdentity requestIdentity,
            List<ModelInteraction> modelInteractions) {

        StateDocument {
            Objects.requireNonNull(runId, "run ID must not be null");
            Objects.requireNonNull(status, "run status must not be null");
            Objects.requireNonNull(currentAttempt, "current attempt must not be null");
            Objects.requireNonNull(budget, "attempt budget must not be null");
            Objects.requireNonNull(finalOutcome, "final outcome must not be null");
            Objects.requireNonNull(runtimeNoticeReason, "runtime notice reason must not be null");
            Objects.requireNonNull(failureReason, "failure reason must not be null");
            Objects.requireNonNull(pendingTerminalResponse, "pending terminal response must not be null");
            Objects.requireNonNull(pendingAnswerVerification, "pending answer verification must not be null");
            Objects.requireNonNull(requestIdentity, "request identity must not be null");
            modelInteractions = List.copyOf(modelInteractions);
        }
    }

    /**
     * 將 attempt 的 complex-key maps 表示為有序 entry arrays
     */
    record RunAttemptDocument(
            AnalysisAttemptId attemptId,
            RevisionVector revisionVector,
            List<MapEntryDocument<CapabilityHandle, CapabilityPolicy>> issuedCapabilities,
            List<MapEntryDocument<CandidateHandle, IssuedCandidate>> issuedCandidates,
            List<MapEntryDocument<EvidenceHandle, IssuedEvidence>> issuedEvidence,
            List<MapEntryDocument<ObservationId, AgentObservation>> observations) {

        RunAttemptDocument {
            Objects.requireNonNull(attemptId, "attempt ID must not be null");
            Objects.requireNonNull(revisionVector, "revision vector must not be null");
            issuedCapabilities = List.copyOf(issuedCapabilities);
            issuedCandidates = List.copyOf(issuedCandidates);
            issuedEvidence = List.copyOf(issuedEvidence);
            observations = List.copyOf(observations);
        }
    }

    /**
     * 將 attempt-started 事件內的 attempt maps 維持 entry arrays
     */
    record AttemptStartedDocument(
            String eventType,
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            RunAttemptDocument newAttempt) {

        AttemptStartedDocument {
            Objects.requireNonNull(eventType, "event type must not be null");
            if (!"ATTEMPT_STARTED".equals(eventType)) {
                throw new IllegalArgumentException("attempt-started document requires its event type");
            }
            Objects.requireNonNull(runId, "run ID must not be null");
            Objects.requireNonNull(attemptId, "attempt ID must not be null");
            Objects.requireNonNull(newAttempt, "new attempt must not be null");
        }
    }

    /**
     * 將 context-issued 事件的 complex-key maps 表示為有序 entry arrays
     */
    record ContextIssuedDocument(
            String eventType,
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            RevisionVector revisions,
            List<MapEntryDocument<CapabilityHandle, CapabilityPolicy>> capabilities,
            List<MapEntryDocument<CandidateHandle, IssuedCandidate>> candidates,
            List<MapEntryDocument<EvidenceHandle, IssuedEvidence>> evidence,
            List<MapEntryDocument<ObservationId, AgentObservation>> observations) {

        ContextIssuedDocument {
            Objects.requireNonNull(eventType, "event type must not be null");
            if (!"CONTEXT_ISSUED".equals(eventType)) {
                throw new IllegalArgumentException("context-issued document requires its event type");
            }
            Objects.requireNonNull(runId, "run ID must not be null");
            Objects.requireNonNull(attemptId, "attempt ID must not be null");
            Objects.requireNonNull(revisions, "revisions must not be null");
            capabilities = List.copyOf(capabilities);
            candidates = List.copyOf(candidates);
            evidence = List.copyOf(evidence);
            observations = List.copyOf(observations);
        }
    }

    /**
     * 可保留 complex key 與 insertion order 的 map entry
     */
    record MapEntryDocument<K, V>(K key, V value) {

        MapEntryDocument {
            Objects.requireNonNull(key, "map entry key must not be null");
            Objects.requireNonNull(value, "map entry value must not be null");
        }
    }
}
