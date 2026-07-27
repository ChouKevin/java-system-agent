package com.java.system.agent.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.inbox.domain.InboxEnqueueRequest;
import com.java.system.agent.inbox.application.InboxRetryPolicy;
import com.java.system.agent.inbox.application.SessionInboxProcessor;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.InboxMessageStatus;
import com.java.system.agent.inbox.domain.InboxProcessingOutcome;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.port.out.InboxIdentityGenerator;
import com.java.system.agent.persistence.document.AgentEventDocumentCodec;
import com.java.system.agent.persistence.document.AgentStateDocumentCodec;
import com.java.system.agent.persistence.jdbc.PostgresAgentTransitionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.runtime.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeCapabilityCatalogAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.runtime.application.AnalysisApplicationService;
import com.java.system.agent.runtime.application.ContextIssuer;
import com.java.system.agent.runtime.application.ValidatedAgentLoop;
import com.java.system.agent.runtime.application.state.AgentStateReducer;
import com.java.system.agent.runtime.application.state.AgentTransitionCommitter;
import com.java.system.agent.runtime.application.validation.AgentActionValidator;
import com.java.system.agent.runtime.application.validation.AnswerDocumentValidator;
import com.java.system.agent.runtime.application.validation.AnswerVerdictValidator;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.in.AnswerQuestionCommand;
import com.java.system.agent.runtime.port.in.AnswerExecutionMode;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
import com.java.system.agent.runtime.port.out.SessionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL durable inbox、terminal state 與 session turn 的 crash recovery 驗證
 */
class PostgresTerminalRecoveryIT extends PostgresIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");

    private JdbcClient jdbcClient;
    private PostgresSessionInboxAdapter inbox;
    private PostgresSessionAdapter sessions;
    private PostgresAgentTransitionAdapter transitions;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        DataSource dataSource = newDataSource();
        jdbcClient = JdbcClient.create(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper objectMapper = new ObjectMapper();
        inbox = new PostgresSessionInboxAdapter(jdbcClient, transactionTemplate, new FixedInboxIdentities());
        sessions = new PostgresSessionAdapter(jdbcClient, transactionTemplate);
        transitions = new PostgresAgentTransitionAdapter(
                jdbcClient,
                transactionTemplate,
                new AgentStateDocumentCodec(objectMapper),
                new AgentEventDocumentCodec(objectMapper));
    }

    @Test
    void retriesTerminalAppendFailuresWithTheSameRunThenReconcilesAndReleasesTheFollower() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which repository should I inspect?"));
        InboxMessage follower = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-2"),
                "What should I inspect next?"));
        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        AnalysisApplicationService service = service(actionCalls, new FailingAppendSessionPort(sessions, 3));
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, service, command(firstClaim).budget(), InboxRetryPolicy.defaults());

        assertThat(processor.process(firstClaim, NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        assertThat(actionCalls).hasValue(1);
        assertThat(eventCount(enqueued.runId())).isEqualTo(4L);
        assertThat(eventCount(enqueued.runId(), "CLARIFICATION_ACCEPTED")).isEqualTo(1L);
        assertThat(sessions.read(enqueued.sessionId()).turns()).isEmpty();
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.PENDING.name());

        InboxMessage secondClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(processor.process(secondClaim, NOW.plusSeconds(1))).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        InboxMessage thirdClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        assertThat(processor.process(thirdClaim, NOW.plusSeconds(3)))
                .isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        InboxMessage reconciliationClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        assertThat(reconciliationClaim.attemptCount()).isEqualTo(4);
        assertThat(processor.process(reconciliationClaim, NOW.plusSeconds(3)))
                .isEqualTo(InboxProcessingOutcome.COMPLETED);

        assertThat(actionCalls).hasValue(1);
        assertThat(eventCount(enqueued.runId())).isEqualTo(5L);
        assertThat(eventCount(enqueued.runId(), "CLARIFICATION_ACCEPTED")).isEqualTo(1L);
        assertThat(eventCount(enqueued.runId(), "RUN_CONCLUDED")).isEqualTo(1L);
        assertThat(sessions.read(enqueued.sessionId()).turns()).hasSize(1);
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
        assertThat(inbox.claimNext(NOW.plusSeconds(3)).orElseThrow().inboxMessageId())
                .isEqualTo(follower.inboxMessageId());
    }

    @Test
    void recoversADurableTurnWithTheSameRunWithoutRepeatingTheTerminalActionOrSessionTurn() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which repository should I inspect?"));
        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        AnalysisApplicationService interruptedService = service(
                actionCalls, new CrashAfterDurableAppendSessionPort(sessions));

        assertThatThrownBy(() -> interruptedService.answer(command(firstClaim)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated process interruption");
        assertThat(actionCalls).hasValue(1);
        assertThat(eventCount(enqueued.runId())).isEqualTo(4L);
        assertThat(eventCount(enqueued.runId(), "CLARIFICATION_ACCEPTED")).isEqualTo(1L);
        assertThat(sessions.read(enqueued.sessionId()).turns()).hasSize(1);
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.PROCESSING.name());

        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(1))).isEqualTo(1);
        InboxMessage recoveredClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        AnalysisApplicationService recoveredService = service(actionCalls, sessions);
        AnswerQuestionResult recovered = recoveredService.answer(command(
                recoveredClaim,
                AnswerExecutionMode.TERMINAL_RECONCILIATION,
                recoveredClaim.attemptCount()));
        inbox.complete(recoveredClaim, NOW.plusSeconds(2));

        assertThat(recovered.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(actionCalls).hasValue(1);
        assertThat(eventCount(enqueued.runId())).isEqualTo(5L);
        assertThat(eventCount(enqueued.runId(), "CLARIFICATION_ACCEPTED")).isEqualTo(1L);
        assertThat(sessions.read(enqueued.sessionId()).turns()).containsExactly(new ConversationTurn(
                enqueued.runId(), enqueued.exactQuestion(), "Which repository should I inspect?",
                ConversationTurnType.CLARIFICATION));
        assertThat(nextTurnSequence(enqueued.sessionId())).isEqualTo(1L);
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
    }

    @Test
    void retriesAnActionProviderFailureWithTheSameRunAndANewAttempt() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which repository should I inspect?"));
        AtomicInteger actionCalls = new AtomicInteger();
        AnalysisApplicationService service = service(context -> {
            if (actionCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated action provider interruption");
            }
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which repository should I inspect?", List.of(), "scope is ambiguous"));
        }, query -> {
            throw new AssertionError("clarification must not execute a semantic query");
        }, sessions, new AnalysisAttemptId("attempt-1"), new AnalysisAttemptId("attempt-2"));
        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, service, command(firstClaim).budget(), InboxRetryPolicy.defaults());

        assertThat(processor.process(firstClaim, NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        InboxMessage retryClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(processor.process(retryClaim, NOW.plusSeconds(1))).isEqualTo(InboxProcessingOutcome.COMPLETED);

        assertThat(actionCalls).hasValue(2);
        assertThat(eventCount(enqueued.runId(), "ATTEMPT_INVALIDATED")).isEqualTo(1L);
        assertThat(eventCount(enqueued.runId(), "ATTEMPT_STARTED")).isEqualTo(2L);
        assertThat(sessions.read(enqueued.sessionId()).turns()).hasSize(1);
    }

    @Test
    void failsAnExhaustedRecoveredNonterminalRunWithoutAFourthExternalAction() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which repository should I inspect?"));
        AtomicInteger actionCalls = new AtomicInteger();
        AnalysisApplicationService interruptedService = service(context -> {
            actionCalls.incrementAndGet();
            throw new IllegalStateException("simulated action provider interruption");
        }, query -> {
            throw new AssertionError("action provider failure must occur before semantic execution");
        }, sessions,
                new AnalysisAttemptId("attempt-1"),
                new AnalysisAttemptId("attempt-2"),
                new AnalysisAttemptId("attempt-3"));

        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();
        assertThatThrownBy(() -> interruptedService.answer(command(firstClaim, AnswerExecutionMode.INITIAL, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated action provider interruption");
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(1))).isEqualTo(1);
        InboxMessage secondClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThatThrownBy(() -> interruptedService.answer(command(secondClaim, AnswerExecutionMode.RETRY, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated action provider interruption");
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(2))).isEqualTo(1);
        InboxMessage thirdClaim = inbox.claimNext(NOW.plusSeconds(2)).orElseThrow();
        assertThatThrownBy(() -> interruptedService.answer(command(thirdClaim, AnswerExecutionMode.RETRY, 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated action provider interruption");
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(3))).isEqualTo(1);
        InboxMessage terminalClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        AnalysisApplicationService terminalOnlyService = service(context -> {
            throw new AssertionError("terminal reconciliation must not request another action");
        }, query -> {
            throw new AssertionError("terminal reconciliation must not execute a semantic query");
        }, sessions, new AnalysisAttemptId("attempt-unused"));
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, terminalOnlyService, command(terminalClaim).budget(), InboxRetryPolicy.defaults());

        assertThat(processor.process(terminalClaim, NOW.plusSeconds(3))).isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(actionCalls).hasValue(3);
        assertThat(eventCount(enqueued.runId(), "ATTEMPT_STARTED")).isEqualTo(3L);
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
    }

    @Test
    void retriesASemanticProviderFailureInANewAttemptWithoutDuplicatingBudgetEvents() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Trace the repository"));
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        AnalysisApplicationService service = semanticRetryService(actionCalls, semanticCalls);
        AttemptBudget budget = new AttemptBudget(3, 0, 2, 0, 1, 0, 1, 0, 1, 0);
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, service, budget, InboxRetryPolicy.defaults());
        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();

        assertThat(processor.process(firstClaim, NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        InboxMessage retryClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(processor.process(retryClaim, NOW.plusSeconds(1))).isEqualTo(InboxProcessingOutcome.COMPLETED);

        AgentRunState state = transitions.findByRunId(enqueued.runId()).orElseThrow();
        assertThat(actionCalls).hasValue(3);
        assertThat(semanticCalls).hasValue(2);
        assertThat(eventCount(enqueued.runId(), "ATTEMPT_INVALIDATED")).isEqualTo(1L);
        assertThat(eventCount(enqueued.runId(), "ATTEMPT_STARTED")).isEqualTo(2L);
        assertThat(eventCount(enqueued.runId(), "ACTION_ACCEPTED")).isEqualTo(2L);
        assertThat(eventCount(enqueued.runId(), "QUERY_BUDGET_CONSUMED")).isEqualTo(2L);
        assertThat(state.budget().usedAgentSteps()).isEqualTo(2);
        assertThat(state.budget().usedQueryExecutions()).isEqualTo(2);
        assertThat(state.budget().usedFinalAnswers()).isEqualTo(1);
        assertThat(sessions.read(enqueued.sessionId()).turns()).hasSize(1);
    }

    @Test
    void reconcilesAnExhaustedRecoveredTerminalRunWithoutAnotherExternalActionOrTurn() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which repository should I inspect?"));
        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(1))).isEqualTo(1);
        InboxMessage secondClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(2))).isEqualTo(1);
        InboxMessage thirdClaim = inbox.claimNext(NOW.plusSeconds(2)).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        AnalysisApplicationService interruptedService = service(
                actionCalls, new CrashAfterDurableAppendSessionPort(sessions));

        assertThatThrownBy(() -> interruptedService.answer(command(
                thirdClaim,
                AnswerExecutionMode.RETRY,
                thirdClaim.attemptCount())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated process interruption");
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(3))).isEqualTo(1);
        InboxMessage reconciliationClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        AnalysisApplicationService reconciliationService = service(actionCalls, sessions);
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, reconciliationService, command(reconciliationClaim).budget(), InboxRetryPolicy.defaults());

        assertThat(reconciliationClaim.attemptCount()).isEqualTo(4);
        assertThat(processor.process(reconciliationClaim, NOW.plusSeconds(3)))
                .isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(actionCalls).hasValue(1);
        assertThat(eventCount(enqueued.runId(), "CLARIFICATION_ACCEPTED")).isEqualTo(1L);
        assertThat(eventCount(enqueued.runId(), "RUN_CONCLUDED")).isEqualTo(1L);
        assertThat(sessions.read(enqueued.sessionId()).turns()).hasSize(1);
        assertThat(nextTurnSequence(enqueued.sessionId())).isEqualTo(1L);
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
    }

    @Test
    void reconcilesAnUnavailablePersistedAnswerVerificationWithoutAnotherExternalCallOrTurn() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which repository should I inspect?"));
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger verifierCalls = new AtomicInteger();
        ValidatedAgentLoop unavailableLoop = new ValidatedAgentLoop(
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(new AnswerAction(new AnswerDocument(List.of(
                            new AnswerStatement(new StatementId("statement-1"), StatementType.QUESTION,
                                    "Which repository should I inspect?", Optional.empty(),
                                    Set.of(), Set.of())))));
                },
                query -> {
                    throw new AssertionError("answer proposal must not execute a semantic query");
                },
                (mode, context) -> {
                    verifierCalls.incrementAndGet();
                    throw new AnswerVerificationUnavailableException("temporary verifier outage", null);
                },
                AnswerVerificationMode.LLM,
                sessions,
                new FakeRepositoryCatalogAdapter(),
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> {
                    throw new AssertionError("answer proposal must not resolve a repository revision");
                },
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();
        SessionInboxProcessor unavailableProcessor = new SessionInboxProcessor(
                inbox, new AnalysisApplicationService(unavailableLoop), command(firstClaim).budget(),
                InboxRetryPolicy.defaults());

        assertThat(unavailableProcessor.process(firstClaim, NOW)).isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        AgentRunState pendingVerification = transitions.findByRunId(enqueued.runId()).orElseThrow();
        assertThat(pendingVerification.pendingAnswerVerification()).isPresent();
        assertThat(eventCount(enqueued.runId(), "ANSWER_PROPOSED")).isEqualTo(1L);

        InboxMessage secondClaim = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(unavailableProcessor.process(secondClaim, NOW.plusSeconds(1)))
                .isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        InboxMessage thirdClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        assertThat(unavailableProcessor.process(thirdClaim, NOW.plusSeconds(3)))
                .isEqualTo(InboxProcessingOutcome.RETRY_SCHEDULED);
        InboxMessage reconciliationClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        AnalysisApplicationService reconciliationService = service(
                context -> {
                    throw new AssertionError("terminal reconciliation must not request another action");
                },
                query -> {
                    throw new AssertionError("terminal reconciliation must not execute a semantic query");
                },
                sessions,
                new AnalysisAttemptId("attempt-unused"));
        SessionInboxProcessor reconciliationProcessor = new SessionInboxProcessor(
                inbox, reconciliationService, command(reconciliationClaim).budget(), InboxRetryPolicy.defaults());

        assertThat(secondClaim.attemptCount()).isEqualTo(2);
        assertThat(thirdClaim.attemptCount()).isEqualTo(3);
        assertThat(reconciliationClaim.attemptCount()).isEqualTo(4);
        assertThat(reconciliationProcessor.process(reconciliationClaim, NOW.plusSeconds(3)))
                .isEqualTo(InboxProcessingOutcome.COMPLETED);
        assertThat(actionCalls).hasValue(1);
        assertThat(verifierCalls).hasValue(3);
        assertThat(eventCount(enqueued.runId(), "ANSWER_VERIFICATION_ABANDONED")).isEqualTo(1L);
        assertThat(eventPayload(enqueued.runId(), "ANSWER_VERIFICATION_ABANDONED"))
                .contains("RETRY_EXHAUSTED");
        assertThat(eventCount(enqueued.runId(), "RUN_CONCLUDED")).isEqualTo(1L);
        assertThat(transitions.findByRunId(enqueued.runId()).orElseThrow().finalOutcome())
                .contains(RunOutcome.FAILED);
        assertThat(sessions.read(enqueued.sessionId()).turns()).isEmpty();
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.COMPLETED.name());
    }

    @Test
    void failsAnExhaustedRecoveredMessageWithoutStateBeforeAnyExternalExecution() {
        InboxMessage enqueued = inbox.enqueue(new InboxEnqueueRequest(
                new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"),
                "Which repository should I inspect?"));
        inbox.claimNext(NOW).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(1))).isEqualTo(1);
        inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(2))).isEqualTo(1);
        inbox.claimNext(NOW.plusSeconds(2)).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(3))).isEqualTo(1);
        InboxMessage reconciliationClaim = inbox.claimNext(NOW.plusSeconds(3)).orElseThrow();
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger verifierCalls = new AtomicInteger();
        ValidatedAgentLoop loop = new ValidatedAgentLoop(
                context -> {
                    actionCalls.incrementAndGet();
                    return new AgentActionProposal.Proposed(
                            new ClarifyAction("Which repository?", List.of(), "scope is ambiguous"));
                },
                query -> {
                    semanticCalls.incrementAndGet();
                    throw new AssertionError("terminal reconciliation must not query semantics");
                },
                (mode, context) -> {
                    verifierCalls.incrementAndGet();
                    throw new AssertionError("terminal reconciliation must not verify an answer");
                },
                AnswerVerificationMode.LLM,
                sessions,
                new FakeRepositoryCatalogAdapter(),
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> {
                    throw new AssertionError("terminal reconciliation must not resolve revisions");
                },
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-unused")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, new AnalysisApplicationService(loop), command(reconciliationClaim).budget(),
                InboxRetryPolicy.defaults());

        assertThat(reconciliationClaim.attemptCount()).isEqualTo(4);
        assertThat(processor.process(reconciliationClaim, NOW.plusSeconds(3)))
                .isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(actionCalls).hasValue(0);
        assertThat(semanticCalls).hasValue(0);
        assertThat(verifierCalls).hasValue(0);
        assertThat(transitions.findByRunId(enqueued.runId())).isEmpty();
        assertThat(eventCount(enqueued.runId())).isZero();
        assertThat(inboxStatus(enqueued.inboxMessageId())).isEqualTo(InboxMessageStatus.FAILED.name());
    }

    private AnalysisApplicationService service(AtomicInteger actionCalls, SessionPort sessionPort) {
        AgentActionPort actionPort = context -> {
            actionCalls.incrementAndGet();
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which repository should I inspect?", List.of(), "scope is ambiguous"));
        };
        return service(actionPort, query -> {
            throw new AssertionError("clarification must not execute a semantic query");
        }, sessionPort, new AnalysisAttemptId("attempt-fixed"));
    }

    private AnalysisApplicationService service(
            AgentActionPort actionPort,
            CapabilityExecutionPort semanticQueryPort,
            SessionPort sessionPort,
            AnalysisAttemptId... attemptIds) {
        ValidatedAgentLoop loop = new ValidatedAgentLoop(
                actionPort,
                semanticQueryPort,
                (mode, context) -> {
                    throw new AssertionError("clarification must not verify an answer");
                },
                AnswerVerificationMode.LLM,
                sessionPort,
                new FakeRepositoryCatalogAdapter(),
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> {
                    throw new AssertionError("clarification must not resolve a repository revision");
                },
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(attemptIds),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
        return new AnalysisApplicationService(loop);
    }

    private AnalysisApplicationService semanticRetryService(
            AtomicInteger actionCalls,
            AtomicInteger semanticCalls) {
        RepositoryId repositoryId = new RepositoryId("repo-1");
        RepositoryRevision revision = new RepositoryRevision("revision-1");
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "inspect",
                "v1",
                Set.of(CandidateKind.REPOSITORY),
                1,
                1,
                new CapabilityQuerySchema(List.of()));
        AgentActionPort actionPort = context -> {
            int actionNumber = actionCalls.incrementAndGet();
            if (actionNumber <= 2) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Trace the repository",
                        Map.of(),
                        "The repository contains the requested flow"));
            }
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which behavior should I trace next?", List.of(), "Query budget is exhausted"));
        };
        CapabilityExecutionPort semanticQueryPort = query -> {
            if (semanticCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated semantic provider interruption");
            }
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        };
        ValidatedAgentLoop loop = new ValidatedAgentLoop(
                actionPort,
                semanticQueryPort,
                (mode, context) -> {
                    throw new AssertionError("clarification must not verify an answer");
                },
                AnswerVerificationMode.LLM,
                sessions,
                new FakeRepositoryCatalogAdapter(new RepositoryDescriptor(repositoryId, "repository one")),
                new FakeCapabilityCatalogAdapter(capability),
                selectedRepositoryId -> RepositoryRevisionResult.ready(revision),
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(
                        new AnalysisAttemptId("attempt-1"),
                        new AnalysisAttemptId("attempt-2")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
        return new AnalysisApplicationService(loop);
    }

    private AnswerQuestionCommand command(InboxMessage message) {
        return new AnswerQuestionCommand(
                message.runId(), message.sessionId(), message.exactQuestion(),
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0));
    }

    private AnswerQuestionCommand command(
            InboxMessage message,
            AnswerExecutionMode executionMode,
            int executionAttempt) {
        return new AnswerQuestionCommand(
                message.runId(), message.sessionId(), message.exactQuestion(),
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0, 1, 0),
                executionMode,
                executionAttempt);
    }

    private long eventCount(AnalysisRunId runId) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM agent_run_event
                WHERE run_id = :runId
                """)
                .param("runId", runId.value())
                .query(Long.class)
                .single();
    }

    private long eventCount(AnalysisRunId runId, String eventType) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM agent_run_event
                WHERE run_id = :runId
                  AND event_type = :eventType
                """)
                .param("runId", runId.value())
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    private String eventPayload(AnalysisRunId runId, String eventType) {
        return jdbcClient.sql("""
                SELECT payload::text
                FROM agent_run_event
                WHERE run_id = :runId
                  AND event_type = :eventType
                """)
                .param("runId", runId.value())
                .param("eventType", eventType)
                .query(String.class)
                .single();
    }

    private String inboxStatus(InboxMessageId inboxMessageId) {
        return jdbcClient.sql("""
                SELECT status
                FROM session_inbox
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .query(String.class)
                .single();
    }

    private long nextTurnSequence(SessionId sessionId) {
        return jdbcClient.sql("""
                SELECT next_turn_sequence
                FROM agent_session
                WHERE session_id = :sessionId
                """)
                .param("sessionId", sessionId.value())
                .query(Long.class)
                .single();
    }

    /**
     * 模擬 session turn 寫入前的基礎設施失敗
     */
    private static final class FailingAppendSessionPort implements SessionPort {

        private final SessionPort delegate;
        private int remainingFailures;

        private FailingAppendSessionPort(SessionPort delegate, int remainingFailures) {
            this.delegate = Objects.requireNonNull(delegate, "session port delegate must not be null");
            this.remainingFailures = remainingFailures;
        }

        @Override
        public SessionHistory read(SessionId sessionId) {
            return delegate.read(sessionId);
        }

        @Override
        public void append(SessionId sessionId, ConversationTurn turn) {
            if (remainingFailures > 0) {
                remainingFailures--;
                throw new IllegalStateException("simulated session append interruption");
            }
            delegate.append(sessionId, turn);
        }
    }

    /**
     * 模擬 session turn 已 durable 而程序在完成 inbox 前中斷的 SessionPort
     */
    private static final class CrashAfterDurableAppendSessionPort implements SessionPort {

        private final SessionPort delegate;
        private boolean crashPending = true;

        private CrashAfterDurableAppendSessionPort(SessionPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "session port delegate must not be null");
        }

        @Override
        public SessionHistory read(SessionId sessionId) {
            return delegate.read(sessionId);
        }

        @Override
        public void append(SessionId sessionId, ConversationTurn turn) {
            delegate.append(sessionId, turn);
            if (crashPending) {
                crashPending = false;
                throw new IllegalStateException("simulated process interruption");
            }
        }
    }

    /**
     * 讓 inbox enqueue 使用固定 session、message 與 run 識別碼的測試 identity generator
     */
    private static final class FixedInboxIdentities implements InboxIdentityGenerator {

        private int inboxNumber;
        private int runNumber;

        @Override
        public InboxMessageId nextInboxMessageId() {
            inboxNumber++;
            return new InboxMessageId("inbox-fixed-" + inboxNumber);
        }

        @Override
        public SessionId nextSessionId() {
            return new SessionId("session-fixed");
        }

        @Override
        public AnalysisRunId nextRunId() {
            runNumber++;
            return new AnalysisRunId("run-fixed-" + runNumber);
        }
    }
}
