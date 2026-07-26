package com.java.system.agent.inbox.application;

import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageStatus;
import com.java.system.agent.inbox.domain.InboxProcessingOutcome;
import com.java.system.agent.inbox.port.out.SessionInboxPort;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.port.in.AnswerQuestionCommand;
import com.java.system.agent.runtime.port.in.AnswerExecutionMode;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.runtime.port.in.AnswerQuestionUseCase;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 處理一筆已認領 inbox 訊息並提交其 durable completion 或 retry transition 的 application service
 */
public final class SessionInboxProcessor {

    private static final InboxFailure ANSWER_EXECUTION_FAILURE = new InboxFailure(
            "ANSWER_EXECUTION_FAILED", "Answer execution did not complete");

    private final SessionInboxPort sessionInboxPort;
    private final AnswerQuestionUseCase answerQuestionUseCase;
    private final AttemptBudget attemptBudget;
    private final InboxRetryPolicy retryPolicy;

    public SessionInboxProcessor(
            SessionInboxPort sessionInboxPort,
            AnswerQuestionUseCase answerQuestionUseCase,
            AttemptBudget attemptBudget,
            InboxRetryPolicy retryPolicy) {
        this.sessionInboxPort = Objects.requireNonNull(sessionInboxPort, "session inbox port must not be null");
        this.answerQuestionUseCase = Objects.requireNonNull(answerQuestionUseCase,
                "answer question use case must not be null");
        this.attemptBudget = Objects.requireNonNull(attemptBudget, "attempt budget must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retry policy must not be null");
    }

    public InboxProcessingOutcome process(InboxMessage claimedMessage, Instant now) {
        Objects.requireNonNull(claimedMessage, "claimed inbox message must not be null");
        Objects.requireNonNull(now, "processing time must not be null");
        if (claimedMessage.status() != InboxMessageStatus.PROCESSING) {
            throw new IllegalArgumentException("inbox processor accepts only processing messages");
        }
        AnswerQuestionCommand command = new AnswerQuestionCommand(
                claimedMessage.runId(), claimedMessage.sessionId(), claimedMessage.exactQuestion(), attemptBudget,
                executionMode(claimedMessage), claimedMessage.attemptCount());
        AnswerQuestionResult answerResult;
        try {
            answerResult = Objects.requireNonNull(
                    answerQuestionUseCase.answer(command), "answer question result must not be null");
        } catch (Exception exception) {
            return handleAnswerFailure(claimedMessage, now);
        }
        sessionInboxPort.complete(claimedMessage, now);
        return InboxProcessingOutcome.COMPLETED;
    }

    private AnswerExecutionMode executionMode(InboxMessage claimedMessage) {
        if (claimedMessage.attemptCount() == 1) {
            return AnswerExecutionMode.INITIAL;
        }
        if (claimedMessage.attemptCount() <= retryPolicy.maxAttempts()) {
            return AnswerExecutionMode.RETRY;
        }
        return AnswerExecutionMode.TERMINAL_RECONCILIATION;
    }

    private InboxProcessingOutcome handleAnswerFailure(InboxMessage claimedMessage, Instant now) {
        Optional<Instant> retryAt = retryPolicy.retryAvailableAt(claimedMessage.attemptCount(), now);
        if (retryAt.isPresent()) {
            sessionInboxPort.retry(claimedMessage, ANSWER_EXECUTION_FAILURE, retryAt.orElseThrow());
            return InboxProcessingOutcome.RETRY_SCHEDULED;
        }
        sessionInboxPort.fail(claimedMessage, ANSWER_EXECUTION_FAILURE, now);
        return InboxProcessingOutcome.FAILED;
    }
}
