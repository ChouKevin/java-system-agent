package com.java.system.agent.interaction.application;

import com.java.system.agent.interaction.domain.InboxFailure;
import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxMessage;
import com.java.system.agent.interaction.domain.InboxMessageStatus;
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.port.out.SessionInboxPort;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.in.AnalysisExecutionDeferredException;
import com.java.system.agent.answering.port.in.AnswerQuestionCommand;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.in.AnswerQuestionUseCase;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;

import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 處理一筆已認領 inbox 訊息並提交其 durable completion 或 retry transition 的 application service
 */
public final class SessionInboxProcessor {

    private static final int MAX_DIAGNOSTIC_CAUSE_DEPTH = 16;
    private static final String FAILED_RESPONSE = "處理失敗，請稍後再試";
    private static final String CANCELLED_RESPONSE = "處理已取消";
    private static final Logger LOGGER = Logger.getLogger(SessionInboxProcessor.class.getName());

    private final SessionInboxPort sessionInboxPort;
    private final AnswerQuestionUseCase answerQuestionUseCase;
    private final AttemptBudget attemptBudget;
    private final InboxRetryPolicy retryPolicy;
    private final InboxLifecycleMetrics metrics;

    public SessionInboxProcessor(
            SessionInboxPort sessionInboxPort,
            AnswerQuestionUseCase answerQuestionUseCase,
            AttemptBudget attemptBudget,
            InboxRetryPolicy retryPolicy) {
        this(sessionInboxPort, answerQuestionUseCase, attemptBudget, retryPolicy, InboxLifecycleMetrics.NO_OP);
    }

    public SessionInboxProcessor(
            SessionInboxPort sessionInboxPort,
            AnswerQuestionUseCase answerQuestionUseCase,
            AttemptBudget attemptBudget,
            InboxRetryPolicy retryPolicy,
            InboxLifecycleMetrics metrics) {
        this.sessionInboxPort = Objects.requireNonNull(sessionInboxPort, "session inbox port must not be null");
        this.answerQuestionUseCase = Objects.requireNonNull(answerQuestionUseCase,
                "answer question use case must not be null");
        this.attemptBudget = Objects.requireNonNull(attemptBudget, "attempt budget must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retry policy must not be null");
        this.metrics = Objects.requireNonNull(metrics, "agent lifecycle metrics must not be null");
    }

    public InboxProcessingOutcome process(InboxClaim claim, Instant now) {
        Objects.requireNonNull(claim, "inbox claim must not be null");
        InboxMessage claimedMessage = claim.message();
        Objects.requireNonNull(claimedMessage, "claimed inbox message must not be null");
        Objects.requireNonNull(now, "processing time must not be null");
        if (claimedMessage.status() != InboxMessageStatus.PROCESSING) {
            throw new IllegalArgumentException("inbox processor accepts only processing messages");
        }
        AnswerQuestionCommand command = new AnswerQuestionCommand(
                claimedMessage.runId(), claimedMessage.sessionId(), claimedMessage.participant(),
                claimedMessage.questionText(), attemptBudget,
                executionMode(claimedMessage), claimedMessage.attemptCount());
        AnswerQuestionResult result;
        try {
            result = Objects.requireNonNull(
                    answerQuestionUseCase.answer(command), "answer question result must not be null");
        } catch (AnalysisExecutionDeferredException exception) {
            metrics.capacityDeferred();
            sessionInboxPort.deferForCapacity(claim, exception.deferral().retryAt());
            return InboxProcessingOutcome.CAPACITY_DEFERRED;
        } catch (AnswerExecutionUnavailableException exception) {
            metrics.infrastructureFailure();
            logFailure("ANSWER_VERIFIER_UNAVAILABLE", claimedMessage, exception);
            return retryOrFail(claim, InboxFailure.ANSWER_VERIFIER_UNAVAILABLE, now);
        } catch (AnswerExecutionContractException exception) {
            InboxFailure failure = exception.failure() == AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT
                    ? InboxFailure.PLANNING_TOOL_CONTRACT
                    : InboxFailure.ANSWER_INTEGRATION_CONTRACT;
            logFailure(failure.code(), claimedMessage, exception);
            sessionInboxPort.failWithFinal(
                    claim, failure, safeResponse(RunOutcome.FAILED), now);
            return InboxProcessingOutcome.FAILED;
        } catch (Exception exception) {
            metrics.infrastructureFailure();
            logFailure("ANSWER_UNEXPECTED", claimedMessage, exception);
            return retryOrFail(claim, InboxFailure.ANSWER_UNEXPECTED, now);
        }
        if (result.responseKind() == RunResponseKind.RUNTIME_NOTICE
                && result.outcome() == RunOutcome.FAILED) {
            sessionInboxPort.failWithFinal(
                    claim, InboxFailure.ANSWER_UNEXPECTED, safeResponse(RunOutcome.FAILED), now);
            return InboxProcessingOutcome.FAILED;
        }
        sessionInboxPort.completeWithFinal(claim, normalizedCompletionResult(result), now);
        return InboxProcessingOutcome.COMPLETED;
    }

    private AnswerExecutionMode executionMode(InboxMessage claimedMessage) {
        if (claimedMessage.attemptCount() > retryPolicy.maxAttempts()) {
            if (claimedMessage.deferReason().isPresent()) {
                logContractViolation("STALE_CAPACITY_TERMINAL_RECONCILIATION", claimedMessage);
            }
            return AnswerExecutionMode.TERMINAL_RECONCILIATION;
        }
        if (claimedMessage.deferReason().isPresent()) {
            return AnswerExecutionMode.CAPACITY_RESUME;
        }
        if (claimedMessage.attemptCount() == 1) {
            return AnswerExecutionMode.INITIAL;
        }
        return AnswerExecutionMode.RETRY;
    }

    private InboxProcessingOutcome retryOrFail(InboxClaim claim, InboxFailure failure, Instant now) {
        InboxMessage claimedMessage = claim.message();
        if (claimedMessage.attemptCount() == retryPolicy.maxAttempts()) {
            sessionInboxPort.retry(claim, failure, now);
            return InboxProcessingOutcome.RETRY_SCHEDULED;
        }
        Optional<Instant> retryAt = retryPolicy.retryAvailableAt(claimedMessage.attemptCount(), now);
        if (retryAt.isPresent()) {
            sessionInboxPort.retry(claim, failure, retryAt.orElseThrow());
            return InboxProcessingOutcome.RETRY_SCHEDULED;
        }
        sessionInboxPort.failWithFinal(claim, failure, safeResponse(RunOutcome.FAILED), now);
        return InboxProcessingOutcome.FAILED;
    }

    private static String safeResponse(RunOutcome outcome) {
        return switch (outcome) {
            case FAILED -> FAILED_RESPONSE;
            case CANCELLED -> CANCELLED_RESPONSE;
            case COMPLETED, INCONCLUSIVE -> throw new IllegalArgumentException("safe response requires a terminal notice outcome");
        };
    }

    private static AnswerQuestionResult normalizedCompletionResult(AnswerQuestionResult result) {
        if (result.responseKind() != RunResponseKind.RUNTIME_NOTICE || result.outcome() != RunOutcome.CANCELLED) {
            return result;
        }
        return new AnswerQuestionResult(
                result.runId(), result.outcome(), safeResponse(result.outcome()), result.answerDocument(),
                result.responseKind(), result.verificationBasis(), result.finalRevisions());
    }

    private static void logContractViolation(String category, InboxMessage claimedMessage) {
        LOGGER.log(Level.WARNING, "answer execution category={0} runId={1} inboxId={2} attempt={3}", new Object[]{
                category,
                claimedMessage.runId().value(),
                claimedMessage.inboxMessageId().value(),
                claimedMessage.attemptCount()});
    }

    private static void logFailure(String category, InboxMessage claimedMessage, Exception exception) {
        LogRecord record = new LogRecord(
                Level.WARNING,
                "answer execution category={0} runId={1} inboxId={2} attempt={3}");
        record.setParameters(new Object[]{
                category,
                claimedMessage.runId().value(),
                claimedMessage.inboxMessageId().value(),
                claimedMessage.attemptCount()});
        record.setThrown(sanitizedDiagnostic(exception));
        LOGGER.log(record);
    }

    private static Throwable sanitizedDiagnostic(Throwable exception) {
        return sanitizedDiagnostic(exception, new IdentityHashMap<>(), 0);
    }

    private static Throwable sanitizedDiagnostic(
            Throwable exception,
            IdentityHashMap<Throwable, Boolean> visited,
            int depth) {
        if (depth >= MAX_DIAGNOSTIC_CAUSE_DEPTH || visited.containsKey(exception)) {
            return new SanitizedDiagnosticException("diagnostic cause chain truncated");
        }
        visited.put(exception, Boolean.TRUE);
        SanitizedDiagnosticException diagnostic = new SanitizedDiagnosticException(exception.getClass().getName());
        diagnostic.setStackTrace(exception.getStackTrace());
        Optional.ofNullable(exception.getCause())
                .map(cause -> sanitizedDiagnostic(cause, visited, Math.incrementExact(depth)))
                .ifPresent(diagnostic::initCause);
        return diagnostic;
    }

    /**
     * 承載已移除原始訊息的 inbox failure 診斷鏈節點
     */
    private static final class SanitizedDiagnosticException extends RuntimeException {

        private SanitizedDiagnosticException(String exceptionType) {
            super("exception type=" + exceptionType);
        }
    }
}
