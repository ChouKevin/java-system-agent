package com.java.system.agent.answering.application.state;

import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.run.PendingTerminalResponse;
import com.java.system.agent.answering.domain.run.PendingAnswerVerification;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.answer.AnswerAcceptance;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.domain.scope.RepositoryId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 唯一把 Agent Run append-only event 轉成下一個狀態的 reducer
 */
public final class AgentStateReducer {

    public AgentTransition reduce(AgentRunState currentState, AgentEvent event) {
        Objects.requireNonNull(currentState, "current agent run state must not be null");
        Objects.requireNonNull(event, "agent event must not be null");
        validateEnvelope(currentState, event);

        AgentRunState candidateState = switch (event) {
            case AgentEvent.RunStarted runStarted -> applyRunStarted(currentState, runStarted);
            case AgentEvent.AttemptStarted attemptStarted -> applyAttemptStarted(currentState, attemptStarted);
            case AgentEvent.ContextIssued contextIssued -> applyContextIssued(currentState, contextIssued);
            case AgentEvent.ActionSelected actionSelected -> applyActionSelected(currentState, actionSelected);
            case AgentEvent.ActionResultRecorded resultRecorded -> applyActionResultRecorded(currentState, resultRecorded);
            case AgentEvent.ActionAccepted actionAccepted -> applyActionAccepted(currentState, actionAccepted);
            case AgentEvent.ActionRejected actionRejected -> applyActionRejected(currentState, actionRejected);
            case AgentEvent.QueryBudgetConsumed queryBudgetConsumed -> applyQueryBudgetConsumed(currentState);
            case AgentEvent.ExecuteBudgetConsumed executeBudgetConsumed -> applyExecuteBudgetConsumed(currentState);
            case AgentEvent.ObservationRecorded observationRecorded -> applyObservationRecorded(currentState,
                    observationRecorded);
            case AgentEvent.AttemptInvalidated attemptInvalidated ->
                    applyAttemptInvalidated(currentState, attemptInvalidated);
            case AgentEvent.AnswerProposed answerProposed -> applyAnswerProposed(currentState, answerProposed);
            case AgentEvent.AnswerAccepted answerAccepted -> applyAnswerAccepted(currentState, answerAccepted);
            case AgentEvent.AnswerRejected answerRejected -> applyAnswerRejected(currentState, answerRejected);
            case AgentEvent.AnswerVerificationAbandoned abandoned -> applyAnswerVerificationAbandoned(currentState, abandoned);
            case AgentEvent.ClarificationAccepted clarificationAccepted -> applyClarificationAccepted(currentState,
                    clarificationAccepted);
            case AgentEvent.RunConcluded runConcluded -> applyRunConcluded(currentState, runConcluded);
        };
        return new AgentTransition(event, candidateState);
    }

    private void validateEnvelope(AgentRunState state, AgentEvent event) {
        if (!state.runId().equals(event.runId())) {
            throw new IllegalArgumentException("agent event belongs to another run");
        }
        if (!state.currentAttempt().attemptId().equals(event.attemptId())) {
            throw new IllegalArgumentException("agent event belongs to another attempt");
        }
        if (state.stateRevision() != event.expectedStateRevision()) {
            throw new StaleAgentStateRevisionException(event.expectedStateRevision(), state.stateRevision());
        }
        if (state.status() == AgentRunStatus.CONCLUDED) {
            throw new IllegalArgumentException("concluded agent run cannot accept events");
        }
    }

    private AgentRunState applyRunStarted(AgentRunState state, AgentEvent.RunStarted event) {
        if (state.status() != AgentRunStatus.STARTING) {
            throw new IllegalArgumentException("agent run is already started");
        }
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), state.budget(),
                state.acceptedActionCount(), state.rejectedActionCount(), state.pendingTerminalResponse(),
                Optional.empty());
    }

    private AgentRunState applyAttemptStarted(AgentRunState state, AgentEvent.AttemptStarted event) {
        RunAttempt newAttempt = event.newAttempt();
        validateAttemptContextRunId(state, newAttempt);
        if (state.status() == AgentRunStatus.RESTARTING) {
            if (newAttempt.attemptId().equals(state.currentAttempt().attemptId())) {
                throw new IllegalArgumentException("restarted agent run requires a different attempt");
            }
            return next(state, AgentRunStatus.RUNNING, newAttempt, Math.incrementExact(state.attemptSequence()),
                    state.budget(), state.acceptedActionCount(), state.rejectedActionCount(),
                    Optional.empty(), Optional.empty());
        }
        if (state.status() != AgentRunStatus.RUNNING || state.stateRevision() != 1
                || !newAttempt.equals(state.currentAttempt())) {
            throw new IllegalArgumentException("initial attempt start must record the current attempt unchanged");
        }
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), state.budget(),
                state.acceptedActionCount(), state.rejectedActionCount(), state.pendingTerminalResponse(),
                Optional.empty());
    }

    private AgentRunState applyContextIssued(AgentRunState state, AgentEvent.ContextIssued event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        validateRevisionExtension(state.currentAttempt().revisionVector(), event.revisions());
        validateBinding(state.runId(), state.currentAttempt().attemptId(), event.revisions(),
                event.capabilities().keySet().stream()
                .map(capabilityHandle -> capabilityHandle.binding()).toList());
        validateBinding(state.runId(), state.currentAttempt().attemptId(), event.revisions(),
                event.candidates().keySet().stream().map(candidateHandle -> candidateHandle.binding()).toList());
        validateBinding(state.runId(), state.currentAttempt().attemptId(), event.revisions(),
                event.evidence().keySet().stream().map(evidenceHandle -> evidenceHandle.binding()).toList());
        validateObservationBindings(state.runId(), state.currentAttempt().attemptId(), event.revisions(),
                event.observations().values());
        RunAttempt attempt = state.currentAttempt().withIssuedContext(event.revisions(), event.capabilities(),
                event.candidates(), event.evidence(), event.observations());
        return next(state, AgentRunStatus.RUNNING, attempt, state.budget(), state.acceptedActionCount(),
                state.rejectedActionCount(), state.pendingTerminalResponse(), Optional.empty());
    }

    private AgentRunState applyActionSelected(AgentRunState state, AgentEvent.ActionSelected event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        return nextWithInteraction(state, new ModelInteraction.ActionSelected(event.attemptId(), event.action()));
    }

    private AgentRunState applyActionResultRecorded(AgentRunState state, AgentEvent.ActionResultRecorded event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        return nextWithInteraction(state, new ModelInteraction.ActionResultRecorded(event.attemptId(), event.result()));
    }

    private AgentRunState applyActionAccepted(AgentRunState state, AgentEvent.ActionAccepted event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        requireSelectedAction(state, event.action(), "accepted action");
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), state.budget().consumeAgentStep(),
                state.acceptedActionCount() + 1, state.rejectedActionCount(), state.pendingTerminalResponse(),
                Optional.empty());
    }

    private AgentRunState applyActionRejected(AgentRunState state, AgentEvent.ActionRejected event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        if (state.pendingAnswerVerification().isPresent()) {
            throw new IllegalArgumentException("generic action rejection is illegal while answer verification is pending");
        }
        AttemptBudget budget = state.budget().consumeActionRejection();
        ModelInteraction interaction;
        if (event.originalAction().isPresent()) {
            AgentAction originalAction = event.originalAction().orElseThrow();
            requireSelectedAction(state, originalAction, "rejected action");
            interaction = new ModelInteraction.ActionResultRecorded(event.attemptId(),
                    new ActionResult.ValidationRejected(event.rejectionCode(), event.description()));
        } else {
            interaction = new ModelInteraction.MalformedResponse(event.attemptId(), event.description());
        }
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), budget,
                state.acceptedActionCount(), state.rejectedActionCount() + 1, state.pendingTerminalResponse(),
                Optional.empty(), state.pendingAnswerVerification(), withInteraction(state, interaction));
    }

    private AgentRunState applyQueryBudgetConsumed(AgentRunState state) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), state.budget().consumeQueryExecution(),
                state.acceptedActionCount(), state.rejectedActionCount(), state.pendingTerminalResponse(),
                Optional.empty());
    }

    private AgentRunState applyExecuteBudgetConsumed(AgentRunState state) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), state.budget().consumeExecuteExecution(),
                state.acceptedActionCount(), state.rejectedActionCount(), state.pendingTerminalResponse(),
                Optional.empty());
    }

    private AgentRunState applyObservationRecorded(AgentRunState state, AgentEvent.ObservationRecorded event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        RunAttempt attempt = state.currentAttempt().withObservation(event.observation());
        return next(state, AgentRunStatus.RUNNING, attempt, state.budget(), state.acceptedActionCount(),
                state.rejectedActionCount(), state.pendingTerminalResponse(), Optional.empty());
    }

    private AgentRunState applyAttemptInvalidated(
            AgentRunState state,
            AgentEvent.AttemptInvalidated event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        AttemptBudget budget = event.consumeRevisionRestart()
                ? state.budget().consumeRevisionRestart()
                : state.budget();
        return next(state, AgentRunStatus.RESTARTING, state.currentAttempt(), budget,
                state.acceptedActionCount(), state.rejectedActionCount(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private AgentRunState applyAnswerAccepted(AgentRunState state, AgentEvent.AnswerAccepted event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        validateTerminalIdentity(state, event.sessionId().value(), event.turn());
        if (state.pendingTerminalResponse().isPresent() || state.pendingAnswerVerification().isEmpty()) {
            throw new IllegalArgumentException("accepted answer requires the pending answer verification checkpoint");
        }
        PendingAnswerVerification pending = state.pendingAnswerVerification().orElseThrow();
        if (!pending.document().equals(event.document())
                || !verificationBasisMatches(pending, event.acceptance())) {
            throw new IllegalArgumentException("accepted answer must match the pending answer verification checkpoint");
        }
        AttemptBudget budget = state.budget().consumeAgentStep();
        requireSelectedAction(state, new AnswerAction(event.document()),
                "accepted answer");
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), budget,
                state.acceptedActionCount() + 1, state.rejectedActionCount(),
                Optional.of(new PendingTerminalResponse.Answer(
                        event.sessionId(), event.turn(), pending.document(), event.acceptance())), Optional.empty(),
                Optional.empty(), withInteraction(state, new ModelInteraction.ActionResultRecorded(event.attemptId(),
                        new ActionResult.AnswerAccepted())));
    }

    private boolean verificationBasisMatches(PendingAnswerVerification pending, AnswerAcceptance acceptance) {
        return switch (pending.verificationMode()) {
            case LLM -> acceptance.verificationBasis() == AnswerVerificationBasis.LLM;
            case CONTRACT_ONLY -> acceptance.verificationBasis() == AnswerVerificationBasis.CONTRACT_ONLY;
        };
    }

    private AgentRunState applyAnswerProposed(AgentRunState state, AgentEvent.AnswerProposed event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        if (!event.proposal().revisions().equals(state.currentAttempt().revisionVector())) {
            throw new IllegalArgumentException(
                    "pending answer verification revisions must match the current attempt revisions");
        }
        if (state.pendingAnswerVerification().isPresent() || state.pendingTerminalResponse().isPresent()) {
            throw new IllegalArgumentException("agent run already has a pending terminal operation");
        }
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), state.budget(),
                state.acceptedActionCount(), state.rejectedActionCount(), Optional.empty(), Optional.empty(),
                Optional.of(event.proposal()));
    }

    private AgentRunState applyAnswerRejected(AgentRunState state, AgentEvent.AnswerRejected event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        if (state.pendingAnswerVerification().isEmpty()) {
            throw new IllegalArgumentException("answer rejection requires a pending answer verification checkpoint");
        }
        PendingAnswerVerification pending = state.pendingAnswerVerification().orElseThrow();
        AttemptBudget budget = state.budget().consumeActionRejection();
        requireSelectedAction(state, new AnswerAction(pending.document()),
                "rejected answer");
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), budget,
                state.acceptedActionCount(), state.rejectedActionCount() + 1, Optional.empty(), Optional.empty(),
                Optional.empty(), withInteraction(state, new ModelInteraction.ActionResultRecorded(event.attemptId(),
                        new ActionResult.AnswerRejected(event.verdict()))));
    }

    private AgentRunState applyAnswerVerificationAbandoned(
            AgentRunState state, AgentEvent.AnswerVerificationAbandoned event) {
        requireRunning(state);
        if (state.pendingAnswerVerification().isEmpty()) {
            throw new IllegalArgumentException("answer verification abandonment requires a pending checkpoint");
        }
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), state.budget(),
                state.acceptedActionCount(), state.rejectedActionCount(), state.pendingTerminalResponse(),
                Optional.empty(), Optional.empty());
    }

    private AgentRunState applyClarificationAccepted(AgentRunState state,
                                                     AgentEvent.ClarificationAccepted event) {
        requireRunning(state);
        requireInitialAttemptStarted(state);
        validateTerminalIdentity(state, event.sessionId().value(), event.turn());
        if (state.pendingTerminalResponse().isPresent()) {
            throw new IllegalArgumentException("agent run already has a pending terminal response");
        }
        AttemptBudget budget = state.budget().consumeAgentStep();
        requireSelectedAction(state, event.action(), "accepted clarification");
        return next(state, AgentRunStatus.RUNNING, state.currentAttempt(), budget,
                state.acceptedActionCount() + 1, state.rejectedActionCount(),
                Optional.of(new PendingTerminalResponse.Clarification(
                        event.sessionId(), event.turn(), event.action())), Optional.empty(), state.pendingAnswerVerification(),
                withInteraction(state, new ModelInteraction.ActionResultRecorded(event.attemptId(),
                        new ActionResult.ClarificationAccepted())));
    }

    private AgentRunState applyRunConcluded(AgentRunState state, AgentEvent.RunConcluded event) {
        requireInitialAttemptStarted(state);
        validateConclusion(state, event);
        return next(state, AgentRunStatus.CONCLUDED, state.currentAttempt(), state.attemptSequence(), state.budget(),
                state.acceptedActionCount(), state.rejectedActionCount(), state.pendingTerminalResponse(),
                Optional.of(event.outcome()), Optional.empty(), event.runtimeNoticeReason(), event.failureReason());
    }

    private void validateConclusion(AgentRunState state, AgentEvent.RunConcluded event) {
        switch (event.outcome()) {
            case COMPLETED -> {
                if (state.pendingTerminalResponse().isEmpty()) {
                    throw new IllegalArgumentException("completed agent run requires a pending answer response");
                }
                if (event.runtimeNoticeReason().isPresent()) {
                    throw new IllegalArgumentException("completed agent run cannot carry a runtime notice");
                }
                if (event.failureReason().isPresent()) {
                    throw new IllegalArgumentException("completed agent run cannot carry a failure reason");
                }
                validateExpectedOutcome(state.pendingTerminalResponse().orElseThrow(), event.outcome());
            }
            case INCONCLUSIVE -> {
                if (state.pendingTerminalResponse().isPresent()) {
                    if (event.runtimeNoticeReason().isPresent()) {
                        throw new IllegalArgumentException("accepted terminal response cannot carry a runtime notice");
                    }
                    if (event.failureReason().isPresent()) {
                        throw new IllegalArgumentException("accepted terminal response cannot carry a failure reason");
                    }
                    validateExpectedOutcome(state.pendingTerminalResponse().orElseThrow(), event.outcome());
                } else if (event.runtimeNoticeReason().isEmpty()) {
                    throw new IllegalArgumentException(
                            "inconclusive agent run requires a pending terminal response or runtime notice");
                }
            }
            case FAILED -> {
                if (state.pendingTerminalResponse().isPresent() || event.runtimeNoticeReason().isPresent()) {
                    throw new IllegalArgumentException(
                            "failed agent run cannot carry terminal content or a runtime notice");
                }
            }
            case CANCELLED -> {
                if (state.pendingTerminalResponse().isPresent() || event.runtimeNoticeReason().isPresent()
                        || event.failureReason().isPresent()) {
                    throw new IllegalArgumentException(
                            "cancelled agent run cannot carry terminal content, a runtime notice, or a failure reason");
                }
            }
        }
    }

    private void validateTerminalIdentity(
            AgentRunState state,
            String sessionIdValue,
            ConversationTurn turn) {
        if (!state.runId().equals(turn.runId())
                || !state.requestIdentity().sessionIdValue().equals(sessionIdValue)
                || !state.requestIdentity().questionText().equals(turn.userMessage())) {
            throw new IllegalArgumentException("accepted terminal response does not match the run request identity");
        }
    }

    private AgentRunState next(AgentRunState state, AgentRunStatus status, RunAttempt attempt,
                               AttemptBudget budget,
                               long acceptedActionCount, long rejectedActionCount,
                               Optional<PendingTerminalResponse> pendingTerminalResponse,
                               Optional<RunOutcome> finalOutcome) {
        return next(state, status, attempt, state.attemptSequence(), budget, acceptedActionCount,
                rejectedActionCount, pendingTerminalResponse, finalOutcome, state.pendingAnswerVerification());
    }

    private AgentRunState next(AgentRunState state, AgentRunStatus status, RunAttempt attempt,
                               AttemptBudget budget, long acceptedActionCount, long rejectedActionCount,
                               Optional<PendingTerminalResponse> pendingTerminalResponse,
                               Optional<RunOutcome> finalOutcome,
                               Optional<PendingAnswerVerification> pendingAnswerVerification) {
        return next(state, status, attempt, state.attemptSequence(), budget, acceptedActionCount,
                rejectedActionCount, pendingTerminalResponse, finalOutcome, pendingAnswerVerification);
    }

    private AgentRunState next(AgentRunState state, AgentRunStatus status, RunAttempt attempt,
                               AttemptBudget budget, long acceptedActionCount, long rejectedActionCount,
                               Optional<PendingTerminalResponse> pendingTerminalResponse,
                               Optional<RunOutcome> finalOutcome,
                               Optional<PendingAnswerVerification> pendingAnswerVerification,
                               List<ModelInteraction> modelInteractions) {
        return next(state, status, attempt, state.attemptSequence(), budget, acceptedActionCount,
                rejectedActionCount, pendingTerminalResponse, finalOutcome, pendingAnswerVerification,
                state.runtimeNoticeReason(), state.failureReason(), modelInteractions);
    }

    private AgentRunState next(AgentRunState state, AgentRunStatus status, RunAttempt attempt,
                               int attemptSequence, AttemptBudget budget,
                               long acceptedActionCount, long rejectedActionCount,
                               Optional<PendingTerminalResponse> pendingTerminalResponse,
                               Optional<RunOutcome> finalOutcome) {
        return next(state, status, attempt, attemptSequence, budget, acceptedActionCount, rejectedActionCount,
                pendingTerminalResponse, finalOutcome, state.pendingAnswerVerification());
    }

    private AgentRunState next(AgentRunState state, AgentRunStatus status, RunAttempt attempt,
                               int attemptSequence, AttemptBudget budget,
                               long acceptedActionCount, long rejectedActionCount,
                               Optional<PendingTerminalResponse> pendingTerminalResponse,
                               Optional<RunOutcome> finalOutcome,
                               Optional<PendingAnswerVerification> pendingAnswerVerification) {
        return new AgentRunState(state.runId(), status, attempt, attemptSequence, budget,
                acceptedActionCount, rejectedActionCount,
                state.stateRevision() + 1, finalOutcome, state.runtimeNoticeReason(), state.failureReason(), pendingTerminalResponse, pendingAnswerVerification,
                state.requestIdentity(), state.modelInteractions());
    }

    private AgentRunState next(AgentRunState state, AgentRunStatus status, RunAttempt attempt,
                               int attemptSequence, AttemptBudget budget,
                               long acceptedActionCount, long rejectedActionCount,
                               Optional<PendingTerminalResponse> pendingTerminalResponse,
                               Optional<RunOutcome> finalOutcome,
                               Optional<PendingAnswerVerification> pendingAnswerVerification,
                               Optional<com.java.system.agent.answering.domain.run.RuntimeNoticeReason> runtimeNoticeReason,
                               Optional<RunFailureReason> failureReason) {
        return next(state, status, attempt, attemptSequence, budget, acceptedActionCount, rejectedActionCount,
                pendingTerminalResponse, finalOutcome, pendingAnswerVerification, runtimeNoticeReason, failureReason,
                state.modelInteractions());
    }

    private AgentRunState next(AgentRunState state, AgentRunStatus status, RunAttempt attempt,
                               int attemptSequence, AttemptBudget budget,
                               long acceptedActionCount, long rejectedActionCount,
                               Optional<PendingTerminalResponse> pendingTerminalResponse,
                               Optional<RunOutcome> finalOutcome,
                               Optional<PendingAnswerVerification> pendingAnswerVerification,
                               Optional<com.java.system.agent.answering.domain.run.RuntimeNoticeReason> runtimeNoticeReason,
                               Optional<RunFailureReason> failureReason,
                               List<ModelInteraction> modelInteractions) {
        return new AgentRunState(state.runId(), status, attempt, attemptSequence, budget,
                acceptedActionCount, rejectedActionCount,
                state.stateRevision() + 1, finalOutcome, runtimeNoticeReason, failureReason, pendingTerminalResponse,
                pendingAnswerVerification, state.requestIdentity(), modelInteractions);
    }

    private AgentRunState nextWithInteraction(AgentRunState state, ModelInteraction interaction) {
        List<ModelInteraction> interactions = withInteraction(state, interaction);
        return next(state, state.status(), state.currentAttempt(), state.attemptSequence(), state.budget(),
                state.acceptedActionCount(), state.rejectedActionCount(), state.pendingTerminalResponse(),
                state.finalOutcome(), state.pendingAnswerVerification(), state.runtimeNoticeReason(),
                state.failureReason(), interactions);
    }

    private List<ModelInteraction> withInteraction(AgentRunState state, ModelInteraction interaction) {
        List<ModelInteraction> interactions = new ArrayList<>(state.modelInteractions());
        interactions.add(interaction);
        return List.copyOf(interactions);
    }

    private void requireSelectedAction(AgentRunState state, AgentAction action,
                                       String description) {
        if (state.unresolvedSelectedAction().filter(action::equals).isEmpty()) {
            throw new IllegalArgumentException(description + " must match the unresolved selected action");
        }
    }

    private void requireRunning(AgentRunState state) {
        if (state.status() != AgentRunStatus.RUNNING) {
            throw new IllegalArgumentException("agent run must be running");
        }
    }

    private void requireInitialAttemptStarted(AgentRunState state) {
        if (state.stateRevision() == 1) {
            throw new IllegalArgumentException("initial attempt must be started before other running events");
        }
    }

    private void validateBinding(AnalysisRunId runId, AnalysisAttemptId attemptId,
                                 RevisionVector revisions, List<HandleBinding> bindings) {
        for (HandleBinding binding : bindings) {
            if (!runId.equals(binding.runId())
                    || !attemptId.equals(binding.attemptId())
                    || !revisions.equals(binding.revisionVector())) {
                throw new IllegalArgumentException("issued context binding does not match the current attempt");
            }
        }
    }

    private void validateObservationBindings(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            RevisionVector revisions,
            Iterable<AgentObservation> observations) {
        for (AgentObservation observation : observations) {
            validateBinding(runId, attemptId, revisions,
                    observation.candidateHandles().stream().map(candidateHandle -> candidateHandle.binding()).toList());
            validateBinding(runId, attemptId, revisions,
                    observation.evidenceHandles().stream().map(evidenceHandle -> evidenceHandle.binding()).toList());
        }
    }

    private void validateExpectedOutcome(PendingTerminalResponse pendingResponse, RunOutcome outcome) {
        if (pendingResponse.expectedOutcome() != outcome) {
            throw new IllegalArgumentException("run conclusion must match the pending response expected outcome");
        }
    }

    private void validateRevisionExtension(RevisionVector current, RevisionVector nextRevisions) {
        for (RepositoryId repositoryId : current.repositoryIds()) {
            if (!nextRevisions.matches(repositoryId, current.revisionOf(repositoryId).orElseThrow())) {
                throw new IllegalArgumentException(
                        "issued context cannot replace or remove a pinned repository revision");
            }
        }
    }

    private void validateAttemptContextRunId(AgentRunState state, RunAttempt attempt) {
        validateRunId(state.runId(), attempt.issuedCapabilities().keySet().stream()
                .map(capabilityHandle -> capabilityHandle.binding()).toList());
        validateRunId(state.runId(), attempt.issuedCandidates().keySet().stream()
                .map(candidateHandle -> candidateHandle.binding()).toList());
        validateRunId(state.runId(), attempt.issuedEvidence().keySet().stream()
                .map(evidenceHandle -> evidenceHandle.binding()).toList());
        for (AgentObservation observation : attempt.observations().values()) {
            validateRunId(state.runId(), observation.candidateHandles().stream()
                    .map(candidateHandle -> candidateHandle.binding()).toList());
            validateRunId(state.runId(), observation.evidenceHandles().stream()
                    .map(evidenceHandle -> evidenceHandle.binding()).toList());
        }
    }

    private void validateRunId(AnalysisRunId runId,
                               List<HandleBinding> bindings) {
        for (HandleBinding binding : bindings) {
            if (!runId.equals(binding.runId())) {
                throw new IllegalArgumentException("attempt context binding belongs to another run");
            }
        }
    }
}
