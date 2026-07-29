package com.java.system.agent.runtime.domain.run;

import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.Objects;
import java.util.Optional;

/**
 * 單一 Agent Run 的可變 authoritative 狀態，不保存對話或模型歷史
 */
public record AgentRunState(
        AnalysisRunId runId,
        AgentRunStatus status,
        RunAttempt currentAttempt,
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
        RunRequestIdentity requestIdentity) {

    public AgentRunState {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(status, "agent run status must not be null");
        Objects.requireNonNull(currentAttempt, "current run attempt must not be null");
        Objects.requireNonNull(budget, "agent run budget must not be null");
        Objects.requireNonNull(finalOutcome, "run final outcome must not be null");
        Objects.requireNonNull(runtimeNoticeReason, "runtime notice reason must not be null");
        Objects.requireNonNull(failureReason, "run failure reason must not be null");
        Objects.requireNonNull(pendingTerminalResponse, "pending terminal response must not be null");
        Objects.requireNonNull(pendingAnswerVerification, "pending answer verification must not be null");
        Objects.requireNonNull(requestIdentity, "run request identity must not be null");
        if (attemptSequence < 1) {
            throw new IllegalArgumentException("agent attempt sequence must be positive");
        }
        if (acceptedActionCount < 0 || rejectedActionCount < 0 || stateRevision < 0) {
            throw new IllegalArgumentException("agent run counters and state revision must not be negative");
        }
        if (status == AgentRunStatus.CONCLUDED && finalOutcome.isEmpty()) {
            throw new IllegalArgumentException("concluded agent run requires a final outcome");
        }
        if (status != AgentRunStatus.CONCLUDED && finalOutcome.isPresent()) {
            throw new IllegalArgumentException("unconcluded agent run cannot have a final outcome");
        }
        if (status != AgentRunStatus.CONCLUDED && runtimeNoticeReason.isPresent()) {
            throw new IllegalArgumentException("unconcluded agent run cannot have a runtime notice reason");
        }
        if (status != AgentRunStatus.CONCLUDED && failureReason.isPresent()) {
            throw new IllegalArgumentException("unconcluded agent run cannot have a failure reason");
        }
        if (runtimeNoticeReason.isPresent()
                && (finalOutcome.isEmpty() || finalOutcome.orElseThrow() != RunOutcome.INCONCLUSIVE)) {
            throw new IllegalArgumentException("runtime notice requires an inconclusive terminal run");
        }
        if (failureReason.isPresent()
                && (finalOutcome.isEmpty() || finalOutcome.orElseThrow() != RunOutcome.FAILED)) {
            throw new IllegalArgumentException("failure reason requires a failed terminal run");
        }
        if (status != AgentRunStatus.RUNNING && status != AgentRunStatus.CONCLUDED
                && pendingTerminalResponse.isPresent()) {
            throw new IllegalArgumentException("only running or concluded agent runs can have a pending terminal response");
        }
        if (pendingTerminalResponse.isPresent()) {
            PendingTerminalResponse pending = pendingTerminalResponse.orElseThrow();
            if (!runId.equals(pending.turn().runId())
                    || !requestIdentity.sessionIdValue().equals(pending.sessionId().value())
                    || !requestIdentity.questionText().equals(pending.turn().userMessage())) {
                throw new IllegalArgumentException("pending terminal response does not match the run request identity");
            }
        }
        if (pendingAnswerVerification.isPresent()) {
            PendingAnswerVerification pending = pendingAnswerVerification.orElseThrow();
            if (status != AgentRunStatus.RUNNING || !currentAttempt.attemptId().equals(pending.attemptId())
                    || !currentAttempt.revisionVector().equals(pending.revisions())
                    || pendingTerminalResponse.isPresent()) {
                throw new IllegalArgumentException("pending answer verification is inconsistent with the running attempt");
            }
        }
        if (status == AgentRunStatus.CONCLUDED && pendingTerminalResponse.isPresent()
                && finalOutcome.orElseThrow() != pendingTerminalResponse.orElseThrow().expectedOutcome()) {
            throw new IllegalArgumentException("concluded pending terminal response must match the final outcome");
        }
        if (status == AgentRunStatus.CONCLUDED
                && finalOutcome.orElseThrow() == RunOutcome.COMPLETED
                && pendingTerminalResponse.isEmpty()) {
            throw new IllegalArgumentException("completed agent run requires its accepted terminal response");
        }
        if (status == AgentRunStatus.CONCLUDED
                && (finalOutcome.orElseThrow() == RunOutcome.FAILED || finalOutcome.orElseThrow() == RunOutcome.CANCELLED)
                && pendingTerminalResponse.isPresent()) {
            throw new IllegalArgumentException("failed or cancelled agent run cannot retain terminal content");
        }
        if (status == AgentRunStatus.STARTING) {
            boolean isBootstrapCoherent = acceptedActionCount == 0
                    && rejectedActionCount == 0
                    && stateRevision == 0
                    && currentAttempt.revisionVector().equals(RevisionVector.empty())
                    && currentAttempt.issuedCapabilities().isEmpty()
                    && currentAttempt.issuedCandidates().isEmpty()
                    && currentAttempt.issuedEvidence().isEmpty()
                    && currentAttempt.observations().isEmpty();
            if (!isBootstrapCoherent) {
                throw new IllegalArgumentException("starting agent run must be bootstrap coherent");
            }
        }
    }

    public static AgentRunState initial(
            AnalysisRunId runId,
            AnalysisAttemptId firstAttemptId,
            int firstAttemptSequence,
            AttemptBudget budget,
            RunRequestIdentity requestIdentity) {
        return new AgentRunState(runId, AgentRunStatus.STARTING, RunAttempt.empty(firstAttemptId),
                firstAttemptSequence, budget,
                0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), requestIdentity);
    }

    public static AgentRunState initial(
            AnalysisRunId runId,
            AnalysisAttemptId firstAttemptId,
            AttemptBudget budget,
            RunRequestIdentity requestIdentity) {
        return initial(runId, firstAttemptId, 1, budget, requestIdentity);
    }

}
