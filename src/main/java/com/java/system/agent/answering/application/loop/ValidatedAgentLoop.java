package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.ActionValidation;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AgentValidationContext;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.AgentAction;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.port.in.AnalysisExecutionDeferredException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.AgentActionContractException;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;
import com.java.system.agent.answering.port.out.HttpMutationPort;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.SessionPort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 唯一推進 bounded action 與 terminal persistence 的 orchestrator
 */
public final class ValidatedAgentLoop {

    public static final String INCONCLUSIVE_RESPONSE = TerminalResponseCoordinator.INCONCLUSIVE_RESPONSE;
    public static final String PLANNING_BUDGET_EXHAUSTED_RESPONSE = TerminalResponseCoordinator.PLANNING_BUDGET_EXHAUSTED_RESPONSE;
    public static final String FAILED_RESPONSE = TerminalResponseCoordinator.FAILED_RESPONSE;
    public static final String CANCELLED_RESPONSE = TerminalResponseCoordinator.CANCELLED_RESPONSE;

    private final AgentActionPort actionPort;
    private final AnalysisCancellationPort cancellationPort;
    private final AgentActionValidator actionValidator;
    private final AgentRunRecoveryCoordinator recoveryCoordinator;
    private final QueryActionExecutor queryActionExecutor;
    private final ExecuteActionExecutor executeActionExecutor;
    private final AnswerActionExecutor answerActionExecutor;
    private final QuestionPlanActionExecutor questionPlanActionExecutor;
    private final TerminalResponseCoordinator terminalResponseCoordinator;
    private final AgentRunTransitions transitions;

    ValidatedAgentLoop(
            AgentActionPort actionPort,
            AnalysisCancellationPort cancellationPort,
            AgentActionValidator actionValidator,
            AgentRunRecoveryCoordinator recoveryCoordinator,
            QueryActionExecutor queryActionExecutor,
            ExecuteActionExecutor executeActionExecutor,
            AnswerActionExecutor answerActionExecutor,
            QuestionPlanActionExecutor questionPlanActionExecutor,
            TerminalResponseCoordinator terminalResponseCoordinator,
            AgentRunTransitions transitions) {
        this.actionPort = Objects.requireNonNull(actionPort, "agent action port must not be null");
        this.cancellationPort = Objects.requireNonNull(cancellationPort, "analysis cancellation port must not be null");
        this.actionValidator = Objects.requireNonNull(actionValidator, "agent action validator must not be null");
        this.recoveryCoordinator = Objects.requireNonNull(
                recoveryCoordinator, "agent run recovery coordinator must not be null");
        this.queryActionExecutor = Objects.requireNonNull(
                queryActionExecutor, "query action executor must not be null");
        this.executeActionExecutor = Objects.requireNonNull(
                executeActionExecutor, "execute action executor must not be null");
        this.answerActionExecutor = Objects.requireNonNull(
                answerActionExecutor, "answer action executor must not be null");
        this.questionPlanActionExecutor = Objects.requireNonNull(
                questionPlanActionExecutor, "question plan action executor must not be null");
        this.terminalResponseCoordinator = Objects.requireNonNull(
                terminalResponseCoordinator, "terminal response coordinator must not be null");
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
    }

    public static ValidatedAgentLoop compose(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            HttpMutationPort httpMutationPort,
            AnswerVerificationPort verificationPort,
            AnswerVerificationMode answerVerificationMode,
            SessionPort sessionPort,
            RepositoryCatalogPort repositoryCatalogPort,
            CapabilityCatalogPort capabilityCatalogPort,
            RepositoryRevisionPort repositoryRevisionPort,
            AnalysisCancellationPort cancellationPort,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            AgentActionValidator actionValidator,
            AnswerDocumentValidator documentValidator,
            AnswerVerdictValidator verdictValidator,
            AgentTransitionCommitter transitionCommitter,
            ContextIssuer contextIssuer) {
        AgentRunTransitions transitions = new AgentRunTransitions(transitionCommitter);
        AgentLoopTelemetry telemetry = new AgentLoopTelemetry(
                capabilityExecutionPort,
                httpMutationPort,
                verificationPort,
                capabilityCatalogPort,
                repositoryCatalogPort,
                repositoryRevisionPort);
        TerminalResponseCoordinator terminalResponseCoordinator = new TerminalResponseCoordinator(transitions, sessionPort);
        AnswerActionExecutor answerActionExecutor = new AnswerActionExecutor(
                telemetry,
                cancellationPort,
                answerVerificationMode,
                documentValidator,
                verdictValidator,
                transitions,
                terminalResponseCoordinator);
        QuestionPlanActionExecutor questionPlanActionExecutor = new QuestionPlanActionExecutor(transitions);
        QueryActionExecutor queryActionExecutor = new QueryActionExecutor(
                telemetry,
                cancellationPort,
                contextIssuer,
                transitions,
                terminalResponseCoordinator);
        ExecuteActionExecutor executeActionExecutor = new ExecuteActionExecutor(
                telemetry,
                cancellationPort,
                transitions,
                terminalResponseCoordinator);
        AgentRunRecoveryCoordinator recoveryCoordinator = new AgentRunRecoveryCoordinator(
                transitions,
                telemetry,
                contextIssuer,
                attemptIdGenerator,
                sessionPort,
                answerActionExecutor,
                terminalResponseCoordinator);
        return new ValidatedAgentLoop(
                actionPort,
                cancellationPort,
                actionValidator,
                recoveryCoordinator,
                queryActionExecutor,
                executeActionExecutor,
                answerActionExecutor,
                questionPlanActionExecutor,
                terminalResponseCoordinator,
                transitions);
    }

    public AgentLoopResult execute(AgentLoopRequest request) {
        Objects.requireNonNull(request, "agent loop request must not be null");
        AgentRunRecoveryOutcome recovered = recoveryCoordinator.recover(request);
        ActiveAgentExecution execution;
        switch (recovered) {
            case AgentRunRecoveryOutcome.Active active -> execution = active.execution();
            case AgentRunRecoveryOutcome.Terminal terminal -> {
                return terminal.result();
            }
        }
        AgentRunState state = execution.state();
        SessionHistory sessionHistory = execution.sessionHistory();
        List<CapabilityPolicy> capabilityCatalog = execution.capabilityCatalog();
        List<RepositoryDescriptor> repositoryCatalog = execution.repositoryCatalog();
        Set<RepositoryId> catalogRepositoryIds = execution.catalogRepositoryIds();
        int attemptSequence = execution.attemptSequence();
        Optional<String> latestRejection = execution.latestRejection();
        while (true) {
            if (cancellationPort.isCancellationRequested(request.runId())) {
                return terminalResponseCoordinator.conclude(
                        state, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty(), Optional.empty());
            }
            Optional<RuntimeNoticeReason> exhaustedBudgetReason = exhaustedBudgetReason(state.budget());
            if (exhaustedBudgetReason.isPresent()) {
                return terminalResponseCoordinator.concludeRuntimeNotice(state, exhaustedBudgetReason.orElseThrow());
            }
            AgentActionProposal proposal;
            try {
                proposal = Objects.requireNonNull(
                        actionPort.nextAction(prompt(request, sessionHistory, state, latestRejection)),
                        "agent action port must return a proposal");
            } catch (AgentActionContractException exception) {
                terminalResponseCoordinator.concludeIntegrationFailure(
                        state, exception, Optional.of(RunFailureReason.PLANNING_TOOL_CONTRACT));
                throw new AnswerExecutionContractException(
                        AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT,
                        "planning tool contract failed", exception);
            } catch (ExternalExecutionDeferredException exception) {
                throw terminalResponseCoordinator.deferredExecution(exception);
            }
            if (cancellationPort.isCancellationRequested(request.runId())) {
                return terminalResponseCoordinator.conclude(
                        state, RunOutcome.CANCELLED, CANCELLED_RESPONSE, Optional.empty(), Optional.empty());
            }
            if (proposal instanceof AgentActionProposal.Malformed malformed) {
                state = reject(state, Optional.empty(), "MALFORMED_RESPONSE", malformed.description());
                state = transitions.recordRuntimeObservation(
                        state, ObservationCode.ACTION_REJECTED, malformed.description(), Set.of(), Set.of(),
                        "agent-action-parser");
                latestRejection = Optional.of(malformed.description());
                continue;
            }
            AgentAction action = ((AgentActionProposal.Proposed) proposal).action();
            state = transitions.apply(state, new AgentEvent.ActionSelected(
                    state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), action));
            ActionValidation validation = actionValidator.validate(action, validationContext(state));
            if (validation instanceof ActionValidation.Rejected rejected) {
                state = reject(state, Optional.of(action), rejected.code().name(), rejected.description());
                state = transitions.recordRuntimeObservation(
                        state, ObservationCode.ACTION_REJECTED, rejected.description(), Set.of(), Set.of(),
                        "agent-action-validator");
                latestRejection = Optional.of(rejected.description());
                continue;
            }
            ActionLaneOutcome outcome = switch (action) {
                case QueryAction queryAction -> queryActionExecutor.execute(
                        request,
                        state,
                        queryAction,
                        attemptSequence,
                        catalogRepositoryIds);
                case AnswerAction answerAction -> answerActionExecutor.execute(
                        request, sessionHistory, state, answerAction, attemptSequence);
                case ClarifyAction clarification -> new ActionLaneOutcome.Terminal(
                        terminalResponseCoordinator.acceptClarification(request, state, clarification));
                case ExecuteAction executeAction -> executeActionExecutor.execute(
                        request, state, executeAction, attemptSequence);
                case PlanAction planAction -> new ActionLaneOutcome.Continue(
                        questionPlanActionExecutor.execute(state, planAction), attemptSequence, Optional.empty());
            };
            switch (outcome) {
                case ActionLaneOutcome.Terminal terminal -> {
                    return terminal.result();
                }
                case ActionLaneOutcome.Continue continued -> {
                    state = continued.state();
                    attemptSequence = continued.attemptSequence();
                    latestRejection = continued.latestRejection();
                }
            }
        }
    }

    private AgentRunState reject(
            AgentRunState state,
            Optional<AgentAction> action,
            String rejectionCode,
            String description) {
        return transitions.apply(state, new AgentEvent.ActionRejected(
                state.runId(),
                state.currentAttempt().attemptId(),
                state.stateRevision(),
                action,
                rejectionCode,
                description));
    }

    private AgentPromptContext prompt(
            AgentLoopRequest request,
            SessionHistory sessionHistory,
            AgentRunState state,
            Optional<String> latestRejection) {
        return new AgentPromptContext(
                request.question(),
                sessionHistory,
                state.runId(),
                state.currentAttempt().attemptId(),
                state.currentAttempt().issuedCapabilities(),
                state.currentAttempt().issuedCandidates(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                state.modelInteractions(),
                latestRejection,
                state.budget());
    }

    private AgentValidationContext validationContext(AgentRunState state) {
        return new AgentValidationContext(
                state.currentAttempt().issuedCapabilities(),
                state.currentAttempt().issuedCandidates(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                state.modelInteractions(),
                transitions.currentBinding(state),
                state.budget(),
                state.questionPlan());
    }

    private Optional<RuntimeNoticeReason> exhaustedBudgetReason(AttemptBudget budget) {
        if (!budget.hasAgentStepRemaining()) {
            return Optional.of(RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED);
        }
        if (!budget.hasQueryExecutionRemaining()) {
            return Optional.of(RuntimeNoticeReason.QUERY_EXECUTION_BUDGET_EXHAUSTED);
        }
        if (!budget.hasActionRejectionRemaining()) {
            return Optional.of(RuntimeNoticeReason.ACTION_REJECTION_BUDGET_EXHAUSTED);
        }
        return Optional.empty();
    }
}
