package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.answer.AnswerAcceptance;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerVerificationBasis;
import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.answering.domain.run.PendingTerminalResponse;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import com.java.system.agent.answering.port.in.AnalysisExecutionDeferredException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;
import com.java.system.agent.answering.port.out.SessionPort;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;

import java.util.Objects;
import java.util.Optional;

/**
 * 協調 terminal acceptance、session append 與可重播 terminal response 的 application 元件
 */
final class TerminalResponseCoordinator {

    static final String INCONCLUSIVE_RESPONSE = "目前資訊不足以產生可驗證的回答";
    static final String PLANNING_BUDGET_EXHAUSTED_RESPONSE = "本次分析已達處理上限，請縮小問題範圍後重試";
    static final String FAILED_RESPONSE = "分析流程發生錯誤，未回傳未驗證內容";
    static final String CANCELLED_RESPONSE = "分析已取消";
    static final String RECOVERY_INTERRUPTED = "RECOVERY_INTERRUPTED";
    static final String RECOVERY_INTERRUPTED_DESCRIPTION =
            "Selected action outcome was not durably known when execution resumed";
    static final String TERMINAL_CANCELLED_INTERRUPTED = "TERMINAL_CANCELLED_INTERRUPTED";
    static final String TERMINAL_CANCELLED_INTERRUPTED_DESCRIPTION =
            "Selected action outcome was not durably known because the run was cancelled";
    static final String TERMINAL_FAILED_INTERRUPTED = "TERMINAL_FAILED_INTERRUPTED";
    static final String TERMINAL_FAILED_INTERRUPTED_DESCRIPTION =
            "Selected action outcome was not durably known because the run failed";
    static final String TERMINAL_INCONCLUSIVE_INTERRUPTED = "TERMINAL_INCONCLUSIVE_INTERRUPTED";
    static final String TERMINAL_INCONCLUSIVE_INTERRUPTED_DESCRIPTION =
            "Selected action outcome was not durably known because the run ended inconclusively";
    static final String TERMINAL_COMPLETED_INTERRUPTED = "TERMINAL_COMPLETED_INTERRUPTED";
    static final String TERMINAL_COMPLETED_INTERRUPTED_DESCRIPTION =
            "Selected action outcome was not durably known because the run completed";

    private final AgentRunTransitions transitions;
    private final SessionPort sessionPort;

    TerminalResponseCoordinator(AgentRunTransitions transitions, SessionPort sessionPort) {
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
        this.sessionPort = Objects.requireNonNull(sessionPort, "session port must not be null");
    }

    AgentLoopResult acceptClarification(
            AgentLoopRequest request,
            AgentRunState currentState,
            ClarifyAction clarification) {
        ConversationTurn turn = new ConversationTurn(
                request.runId(), request.participant(), request.question(), clarification.question(),
                ConversationTurnType.CLARIFICATION);
        AgentRunState state;
        try {
            state = transitions.applyTerminalAcceptance(currentState, new AgentEvent.ClarificationAccepted(
                    currentState.runId(), currentState.currentAttempt().attemptId(), currentState.stateRevision(),
                    clarification, request.sessionId(), turn));
        } catch (TerminalAcceptanceCancelledException exception) {
            return conclude(currentState, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty(), Optional.empty());
        }
        sessionPort.append(request.sessionId(), turn);
        return conclude(state, RunOutcome.INCONCLUSIVE, clarification.question(), Optional.empty(), Optional.empty());
    }

    AgentLoopResult acceptAnswer(
            AgentLoopRequest request,
            AgentRunState currentState,
            AnswerAction action,
            AnswerAcceptance acceptance) {
        AnswerDocument document = action.document();
        String rendered = document.renderParagraphs();
        ConversationTurn turn = new ConversationTurn(
                request.runId(), request.participant(), request.question(), rendered, ConversationTurnType.ANSWER);
        AgentRunState state;
        try {
            state = transitions.applyTerminalAcceptance(currentState, new AgentEvent.AnswerAccepted(
                    currentState.runId(), currentState.currentAttempt().attemptId(), currentState.stateRevision(), action,
                    acceptance, request.sessionId(), turn));
        } catch (TerminalAcceptanceCancelledException exception) {
            AgentRunState abandoned = currentState.pendingAnswerVerification().isPresent()
                    ? transitions.abandonAnswerVerification(currentState, AnswerVerificationAbandonReason.CANCELLED)
                    : currentState;
            return conclude(abandoned, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty(), Optional.empty());
        }
        sessionPort.append(request.sessionId(), turn);
        return conclude(state, acceptance.expectedOutcome(), rendered, Optional.of(document), Optional.empty());
    }

    AgentLoopResult conclude(
            AgentRunState state,
            RunOutcome outcome,
            String responseText,
            Optional<AnswerDocument> document,
            Optional<RunFailureReason> failureReason) {
        AgentRunState closed = closeUnresolvedActionForTerminal(state, outcome);
        AgentRunState concluded = transitions.apply(closed, new AgentEvent.RunConcluded(
                closed.runId(), closed.currentAttempt().attemptId(), closed.stateRevision(), outcome,
                Optional.empty(), failureReason));
        return loopResult(concluded, responseText, document);
    }

    AgentLoopResult concludeRuntimeNotice(AgentRunState state, RuntimeNoticeReason reason) {
        AgentRunState closed = closeUnresolvedActionForTerminal(state, RunOutcome.INCONCLUSIVE);
        AgentRunState concluded = transitions.apply(closed, new AgentEvent.RunConcluded(
                closed.runId(), closed.currentAttempt().attemptId(), closed.stateRevision(),
                RunOutcome.INCONCLUSIVE, Optional.of(reason), Optional.empty()));
        return loopResult(concluded, runtimeNoticeResponse(Optional.of(reason), RunOutcome.INCONCLUSIVE), Optional.empty());
    }

    AgentRunState closeRecoveredUnresolvedAction(AgentRunState state) {
        return closeUnresolvedAction(state, RECOVERY_INTERRUPTED, RECOVERY_INTERRUPTED_DESCRIPTION);
    }

    AgentLoopResult fromConcludedState(AgentRunState state) {
        if (state.failureReason().isPresent()) {
            throw switch (state.failureReason().orElseThrow()) {
                case PLANNING_TOOL_CONTRACT -> new AnswerExecutionContractException(
                        AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT,
                        "planning tool contract failed",
                        null); // cs-allow
                case HTTP_MUTATION_CONTRACT -> new AnswerExecutionContractException(
                        AnswerExecutionContractFailure.HTTP_MUTATION_CONTRACT,
                        "HTTP mutation contract failed",
                        null); // cs-allow
            };
        }
        RunOutcome outcome = state.finalOutcome().orElseThrow();
        if (state.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = state.pendingTerminalResponse().orElseThrow();
            return loopResult(state, pending.turn().assistantMessage(), pendingAnswerDocument(pending));
        }
        return loopResult(state, runtimeNoticeResponse(state.runtimeNoticeReason(), outcome), Optional.empty());
    }

    AgentLoopResult concludeIntegrationFailure(
            AgentRunState state,
            RuntimeException exception) {
        return concludeIntegrationFailure(state, exception, Optional.empty());
    }

    AgentLoopResult concludeIntegrationFailure(
            AgentRunState state,
            RuntimeException exception,
            Optional<RunFailureReason> failureReason) {
        try {
            AgentRunState abandoned = state.pendingAnswerVerification().isPresent()
                    ? transitions.abandonAnswerVerification(state, AnswerVerificationAbandonReason.INTEGRATION_CONTRACT_FAILURE)
                    : state;
            return conclude(abandoned, RunOutcome.FAILED, FAILED_RESPONSE, Optional.empty(), failureReason);
        } catch (AgentTransitionConflictException | AgentLoopException | AgentRunInProgressException commitFailure) {
            AnswerExecutionContractException contractFailure = new AnswerExecutionContractException(
                    "integration contract failure could not be concluded", commitFailure);
            contractFailure.addSuppressed(exception);
            throw contractFailure;
        }
    }

    AnalysisExecutionDeferredException deferredExecution(ExternalExecutionDeferredException exception) {
        return new AnalysisExecutionDeferredException(exception.deferral());
    }

    AgentLoopResult concludePersistedTerminal(AgentRunState state, PendingTerminalResponse pending) {
        sessionPort.append(pending.sessionId(), pending.turn());
        return conclude(
                state,
                pending.expectedOutcome(),
                pending.turn().assistantMessage(),
                pendingAnswerDocument(pending),
                Optional.empty());
    }

    private AgentRunState closeUnresolvedActionForTerminal(AgentRunState state, RunOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> closeUnresolvedAction(
                    state, TERMINAL_CANCELLED_INTERRUPTED, TERMINAL_CANCELLED_INTERRUPTED_DESCRIPTION);
            case FAILED -> closeUnresolvedAction(
                    state, TERMINAL_FAILED_INTERRUPTED, TERMINAL_FAILED_INTERRUPTED_DESCRIPTION);
            case INCONCLUSIVE -> closeUnresolvedAction(
                    state, TERMINAL_INCONCLUSIVE_INTERRUPTED, TERMINAL_INCONCLUSIVE_INTERRUPTED_DESCRIPTION);
            case COMPLETED -> closeUnresolvedAction(
                    state, TERMINAL_COMPLETED_INTERRUPTED, TERMINAL_COMPLETED_INTERRUPTED_DESCRIPTION);
        };
    }

    private AgentRunState closeUnresolvedAction(AgentRunState state, String code, String description) {
        if (state.unresolvedSelectedAction().isEmpty()) {
            return state;
        }
        return transitions.apply(state, new AgentEvent.ActionResultRecorded(
                state.runId(), state.currentAttempt().attemptId(), state.stateRevision(),
                new ActionResult.ActionInterrupted(code, description)));
    }

    private Optional<AnswerDocument> pendingAnswerDocument(PendingTerminalResponse pending) {
        if (pending instanceof PendingTerminalResponse.Answer answer) {
            return Optional.of(answer.document());
        }
        return Optional.empty();
    }

    private String runtimeNoticeResponse(Optional<RuntimeNoticeReason> reason, RunOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> throw new IllegalStateException("completed agent run must retain its accepted answer");
            case INCONCLUSIVE -> switch (reason.orElseThrow(
                    () -> new IllegalStateException("inconclusive runtime notice requires a reason"))) {
                case INSUFFICIENT_VERIFIABLE_INFORMATION -> INCONCLUSIVE_RESPONSE;
                case AGENT_STEP_BUDGET_EXHAUSTED,
                        QUERY_EXECUTION_BUDGET_EXHAUSTED,
                        ACTION_REJECTION_BUDGET_EXHAUSTED -> PLANNING_BUDGET_EXHAUSTED_RESPONSE;
            };
            case FAILED -> FAILED_RESPONSE;
            case CANCELLED -> CANCELLED_RESPONSE;
        };
    }

    private AgentLoopResult loopResult(
            AgentRunState state,
            String responseText,
            Optional<AnswerDocument> document) {
        RunResponseKind responseKind = RunResponseKind.RUNTIME_NOTICE;
        Optional<AnswerVerificationBasis> verificationBasis = Optional.empty();
        if (state.pendingTerminalResponse().isPresent()) {
            PendingTerminalResponse pending = state.pendingTerminalResponse().orElseThrow();
            if (pending instanceof PendingTerminalResponse.Answer answer) {
                responseKind = RunResponseKind.ANSWER;
                verificationBasis = Optional.of(answer.acceptance().verificationBasis());
            } else {
                responseKind = RunResponseKind.CLARIFICATION;
            }
        }
        return new AgentLoopResult(
                state.runId(),
                state.finalOutcome().orElseThrow(),
                responseText,
                document,
                responseKind,
                verificationBasis,
                state.currentAttempt().revisionVector());
    }
}
