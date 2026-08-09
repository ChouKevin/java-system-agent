package com.java.system.agent.answering.domain.run;

import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 單一 Agent Run 的可變 authoritative 狀態，保存 run 級模型動作歷史但不保存對話
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
        RunRequestIdentity requestIdentity,
        List<ModelInteraction> modelInteractions) {

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
        modelInteractions = immutableModelInteractions(modelInteractions);
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
                    && currentAttempt.observations().isEmpty()
                    && modelInteractions.isEmpty();
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
                0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), requestIdentity,
                List.of());
    }

    public static AgentRunState initial(
            AnalysisRunId runId,
            AnalysisAttemptId firstAttemptId,
            AttemptBudget budget,
            RunRequestIdentity requestIdentity) {
        return initial(runId, firstAttemptId, 1, budget, requestIdentity);
    }

    /**
     * 回傳目前 attempt 尚未以結果關閉的模型動作
     */
    public Optional<AgentAction> unresolvedSelectedAction() {
        Optional<AgentAction> unresolved = Optional.empty();
        for (ModelInteraction interaction : modelInteractions) {
            if (interaction instanceof ModelInteraction.ActionSelected selected
                    && selected.attemptId().equals(currentAttempt.attemptId())) {
                unresolved = Optional.of(selected.action());
            } else if (interaction instanceof ModelInteraction.ActionResultRecorded recorded
                    && recorded.attemptId().equals(currentAttempt.attemptId())) {
                unresolved = Optional.empty();
            }
        }
        return unresolved;
    }

    private static List<ModelInteraction> immutableModelInteractions(List<ModelInteraction> interactions) {
        Objects.requireNonNull(interactions, "model interactions must not be null");
        List<ModelInteraction> copied = new ArrayList<>();
        Map<AnalysisAttemptId, AgentAction> unresolvedSelections = new HashMap<>();
        for (ModelInteraction interaction : interactions) {
            ModelInteraction checkedInteraction = Objects.requireNonNull(interaction,
                    "model interactions must not contain null values");
            if (checkedInteraction instanceof ModelInteraction.ActionSelected selected) {
                AgentAction existing = unresolvedSelections.putIfAbsent(selected.attemptId(), selected.action());
                if (Objects.nonNull(existing)) {
                    throw new IllegalArgumentException("attempt cannot select another action while one is unresolved");
                }
            } else if (checkedInteraction instanceof ModelInteraction.ActionResultRecorded recorded) {
                AgentAction selected = unresolvedSelections.get(recorded.attemptId());
                if (Objects.isNull(selected)) {
                    throw new IllegalArgumentException("action result requires an unresolved selected action");
                }
                if (!recorded.result().matches(selected)) {
                    throw new IllegalArgumentException("action result does not match its unresolved selected action");
                }
                unresolvedSelections.remove(recorded.attemptId());
            }
            copied.add(checkedInteraction);
        }
        return List.copyOf(copied);
    }

}
