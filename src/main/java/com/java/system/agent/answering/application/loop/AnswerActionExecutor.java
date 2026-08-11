package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.application.validation.AnswerDocumentValidation;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.answer.AnswerAcceptance;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementVerdict;
import com.java.system.agent.answering.domain.answer.StatementVerdictStatus;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.evidence.IssuedEvidence;
import com.java.system.agent.answering.domain.handle.HandleBinding;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.EvidenceCapabilityProvenance;
import com.java.system.agent.answering.domain.run.AnswerVerificationAbandonReason;
import com.java.system.agent.answering.domain.run.PendingAnswerVerification;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationContractException;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.ExternalExecutionDeferredException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 執行 ANSWER action 的驗證、verifier checkpoint 與 terminal acceptance application 元件
 */
final class AnswerActionExecutor {

    private final AgentLoopTelemetry telemetry;
    private final AnalysisCancellationPort cancellationPort;
    private final AnswerVerificationMode answerVerificationMode;
    private final AnswerDocumentValidator documentValidator;
    private final AnswerVerdictValidator verdictValidator;
    private final AgentRunTransitions transitions;
    private final TerminalResponseCoordinator terminalResponseCoordinator;

    AnswerActionExecutor(
            AgentLoopTelemetry telemetry,
            AnalysisCancellationPort cancellationPort,
            AnswerVerificationMode answerVerificationMode,
            AnswerDocumentValidator documentValidator,
            AnswerVerdictValidator verdictValidator,
            AgentRunTransitions transitions,
            TerminalResponseCoordinator terminalResponseCoordinator) {
        this.telemetry = Objects.requireNonNull(telemetry, "agent loop telemetry must not be null");
        this.cancellationPort = Objects.requireNonNull(cancellationPort, "analysis cancellation port must not be null");
        this.answerVerificationMode = Objects.requireNonNull(
                answerVerificationMode, "answer verification mode must not be null");
        this.documentValidator = Objects.requireNonNull(documentValidator, "answer document validator must not be null");
        this.verdictValidator = Objects.requireNonNull(verdictValidator, "answer verdict validator must not be null");
        this.transitions = Objects.requireNonNull(transitions, "agent run transitions must not be null");
        this.terminalResponseCoordinator = Objects.requireNonNull(
                terminalResponseCoordinator, "terminal response coordinator must not be null");
    }

    ActionLaneOutcome execute(
            AgentLoopRequest request,
            SessionHistory history,
            AgentRunState currentState,
            AnswerAction action,
            int attemptSequence) {
        AgentRunState state = currentState;
        if (cancellationPort.isCancellationRequested(request.runId())) {
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.conclude(
                    state, RunOutcome.CANCELLED, TerminalResponseCoordinator.CANCELLED_RESPONSE,
                    Optional.empty(), Optional.empty()));
        }
        HandleBinding binding = transitions.currentBinding(state);
        documentValidator.validate(
                action.document(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                binding);
        if (answerVerificationMode == AnswerVerificationMode.CONTRACT_ONLY) {
            return new ActionLaneOutcome.Terminal(
                    terminalResponseCoordinator.acceptAnswer(request, state, action, AnswerAcceptance.contractOnly()));
        }
        PendingAnswerVerification pendingVerification = new PendingAnswerVerification(
                state.currentAttempt().attemptId(), state.currentAttempt().revisionVector(), action,
                answerVerificationMode);
        state = transitions.apply(state, new AgentEvent.AnswerProposed(state.runId(), state.currentAttempt().attemptId(),
                state.stateRevision(), pendingVerification));
        return verifyPendingAnswer(request, history, state, attemptSequence);
    }

    ActionLaneOutcome resumePending(
            AgentLoopRequest request,
            SessionHistory history,
            AgentRunState state) {
        return verifyPendingAnswer(request, history, state, state.attemptSequence());
    }

    private ActionLaneOutcome verifyPendingAnswer(
            AgentLoopRequest request,
            SessionHistory sessionHistory,
            AgentRunState currentState,
            int attemptSequence) {
        PendingAnswerVerification pending = currentState.pendingAnswerVerification().orElseThrow(
                () -> new IllegalStateException("answer verification checkpoint is required"));
        AgentRunState state = currentState;
        if (cancellationPort.isCancellationRequested(request.runId())) {
            state = transitions.abandonAnswerVerification(state, AnswerVerificationAbandonReason.CANCELLED);
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.conclude(
                    state, RunOutcome.CANCELLED, TerminalResponseCoordinator.CANCELLED_RESPONSE,
                    Optional.empty(), Optional.empty()));
        }
        HandleBinding binding = transitions.currentBinding(state);
        AnswerDocumentValidation documentValidation = documentValidator.validate(
                pending.action().document(),
                state.currentAttempt().issuedEvidence(),
                state.currentAttempt().observations(),
                binding);
        AnswerVerificationContext verificationContext = new AnswerVerificationContext(
                request.question(),
                sessionHistory,
                pending.action().document(),
                List.copyOf(state.currentAttempt().issuedEvidence().values()),
                List.copyOf(state.currentAttempt().observations().values()),
                List.copyOf(documentValidation.citedEvidence().values()),
                List.copyOf(documentValidation.referencedObservations().values()),
                EvidenceCapabilityProvenance.resolve(
                        state.currentAttempt().issuedCapabilities(),
                        state.currentAttempt().issuedEvidence(),
                        state.modelInteractions()),
                state.questionPlan().orElseThrow(
                        () -> new IllegalStateException("LLM answer verification requires a question plan")),
                pending.action().resolutions());
        AnswerVerificationResult verificationResult;
        try {
            verificationResult = telemetry.verifyAnswer(state, AnswerVerificationMode.LLM, verificationContext);
        } catch (ExternalExecutionDeferredException exception) {
            throw terminalResponseCoordinator.deferredExecution(exception);
        } catch (AnswerVerificationUnavailableException exception) {
            throw new AnswerExecutionUnavailableException("answer verification is unavailable", exception);
        } catch (AnswerVerificationContractException exception) {
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.concludeIntegrationFailure(state, exception));
        }
        if (cancellationPort.isCancellationRequested(request.runId())) {
            state = transitions.abandonAnswerVerification(state, AnswerVerificationAbandonReason.CANCELLED);
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.conclude(
                    state, RunOutcome.CANCELLED, TerminalResponseCoordinator.CANCELLED_RESPONSE,
                    Optional.empty(), Optional.empty()));
        }
        AnswerAcceptance acceptance;
        if (verificationResult instanceof AnswerVerificationResult.LlmVerdict llmVerdict) {
            AnswerVerdict verdict = llmVerdict.verdict();
            try {
                verdictValidator.validate(documentValidation, verdict);
            } catch (IllegalArgumentException exception) {
                return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.concludeIntegrationFailure(state, exception));
            }
            if (verdict.disposition() == AnswerDisposition.REJECTED) {
                String rejection = rejectionDescription(verdict);
                state = transitions.apply(state, new AgentEvent.AnswerRejected(
                        state.runId(), state.currentAttempt().attemptId(), state.stateRevision(), verdict));
                state = recordAnswerRejectionObservations(state, pending.action().document(), verdict);
                return new ActionLaneOutcome.Continue(state, attemptSequence, Optional.of(rejection));
            }
            acceptance = AnswerAcceptance.llm(verdict);
        } else {
            return new ActionLaneOutcome.Terminal(terminalResponseCoordinator.concludeIntegrationFailure(
                    state,
                    new AnswerVerificationContractException("answer verification returned an incompatible result")));
        }
        return new ActionLaneOutcome.Terminal(
                terminalResponseCoordinator.acceptAnswer(request, state, pending.action(), acceptance));
    }

    private AgentRunState recordAnswerRejectionObservations(
            AgentRunState initialState,
            AnswerDocument document,
            AnswerVerdict verdict) {
        AgentRunState state = initialState;
        Map<StatementId, AnswerStatement> statements = new LinkedHashMap<>();
        document.statements().forEach(statement -> statements.put(statement.statementId(), statement));
        for (StatementVerdict statementVerdict : verdict.statementVerdicts()) {
            if (statementVerdict.status() == StatementVerdictStatus.UNSUPPORTED) {
                AnswerStatement statement = statements.get(statementVerdict.statementId());
                AgentRunState currentState = state;
                state = transitions.recordRuntimeObservation(
                        state,
                        ObservationCode.UNSUPPORTED_CLAIM,
                        statementVerdict.description(),
                        Set.of(),
                        statement.citations().stream()
                                .map(reference -> findIssuedEvidence(currentState.currentAttempt(), reference.value()).handle())
                                .collect(Collectors.toSet()),
                        "answer-verifier");
            }
        }
        for (String unaddressedPart : verdict.unaddressedParts()) {
            state = transitions.recordRuntimeObservation(
                    state, ObservationCode.UNADDRESSED_PART, unaddressedPart,
                    Set.of(), Set.of(), "answer-verifier");
        }
        for (String blockingUncertainty : verdict.blockingUncertainties()) {
            state = transitions.recordRuntimeObservation(
                    state, ObservationCode.BLOCKING_UNCERTAINTY, blockingUncertainty,
                    Set.of(), Set.of(), "answer-verifier");
        }
        for (String rejectionReason : verdict.rejectionReasons()) {
            state = transitions.recordRuntimeObservation(
                    state, ObservationCode.ANSWER_REJECTION_REASON, rejectionReason,
                    Set.of(), Set.of(), "answer-verifier");
        }
        return state;
    }

    private IssuedEvidence findIssuedEvidence(RunAttempt attempt, String handleValue) {
        return attempt.issuedEvidence().values().stream()
                .filter(evidence -> evidence.handle().value().equals(handleValue))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("accepted evidence is absent after context reissue"));
    }

    private String rejectionDescription(AnswerVerdict verdict) {
        List<String> descriptions = new ArrayList<>();
        verdict.unaddressedParts().forEach(part -> descriptions.add("unaddressed part: " + part));
        verdict.blockingUncertainties().forEach(uncertainty ->
                descriptions.add("blocking uncertainty: " + uncertainty));
        verdict.rejectionReasons().forEach(reason -> descriptions.add("rejection reason: " + reason));
        if (!descriptions.isEmpty()) {
            return String.join("; ", descriptions);
        }
        return "answer verifier rejected the proposed document";
    }
}
