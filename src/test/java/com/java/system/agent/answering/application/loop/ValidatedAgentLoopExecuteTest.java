package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeCapabilityCatalogAdapter;
import com.java.system.agent.answering.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.ActionRejectionCode;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.ClarifyAction;
import com.java.system.agent.answering.domain.action.ExecuteAction;
import com.java.system.agent.answering.domain.action.ExternalHttpMethod;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.HttpMutationPort;
import com.java.system.agent.answering.port.out.HttpMutationResult;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ValidatedAgentLoop EXECUTE preview 的持久化與後續規劃測試
 */
class ValidatedAgentLoopExecuteTest {

    @Test
    void recordsNotImplementedExecutePreviewThenContinuesToClarification() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger mutationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        String targetUrl = "https://example.invalid/preview";
        String jsonBody = "{\"preview\":true}";
        AgentActionPort actions = context -> {
            if (actionCalls.getAndIncrement() == 0) {
                return new AgentActionProposal.Proposed(new ExecuteAction(
                        ExternalHttpMethod.POST,
                        targetUrl,
                        Optional.of(jsonBody),
                        "Preview the requested HTTP mutation"));
            }
            return new AgentActionProposal.Proposed(new ClarifyAction(
                    "Which environment should receive the change?", List.of(), "Need the target environment"));
        };
        HttpMutationPort mutations = action -> {
            mutationCalls.incrementAndGet();
            return new HttpMutationResult.NotImplemented();
        };
        ValidatedAgentLoop loop = loop(actions, mutations, new FakeCancellationAdapter(), transitions);

        AgentLoopResult result = loop.execute(new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                new ParticipantRef("test", "participant-1"),
                "Preview this HTTP mutation",
                new AttemptBudget(3, 0, 1, 0, 1, 0, 1, 0, 1, 0)));

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(result.responseKind()).isEqualTo(RunResponseKind.CLARIFICATION);
        assertThat(result.responseText()).isEqualTo("Which environment should receive the change?");
        assertThat(actionCalls).hasValue(2);
        assertThat(mutationCalls).hasValue(1);
        List<Class<?>> relevantEventTypes = new ArrayList<>();
        for (AgentEvent event : transitions.events()) {
            if (event instanceof AgentEvent.ActionAccepted
                    || event instanceof AgentEvent.ExecuteBudgetConsumed
                    || event instanceof AgentEvent.ObservationRecorded) {
                relevantEventTypes.add(event.getClass());
            }
        }
        assertThat(relevantEventTypes)
                .containsExactly(
                        AgentEvent.ActionAccepted.class,
                        AgentEvent.ExecuteBudgetConsumed.class,
                        AgentEvent.ObservationRecorded.class);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ObservationRecorded.class::isInstance)
                .singleElement()
                .satisfies(event -> {
                    AgentEvent.ObservationRecorded recorded = (AgentEvent.ObservationRecorded) event;
                    assertThat(recorded.observation().source()).isEqualTo(ObservationSource.HTTP_MUTATION);
                    assertThat(recorded.observation().code()).isEqualTo(ObservationCode.EXECUTION_NOT_IMPLEMENTED);
                    assertThat(recorded.observation().provenance()).isEqualTo("http-mutation-port");
                    assertThat(recorded.observation().description())
                            .isEqualTo(ExecuteActionExecutor.NOT_IMPLEMENTED_OBSERVATION)
                            .doesNotContain(targetUrl, jsonBody);
                    assertThat(recorded.observation().candidateHandles()).isEmpty();
                    assertThat(recorded.observation().evidenceHandles()).isEmpty();
                });
    }

    @Test
    void cancelsAfterExecuteAcceptanceAndBudgetConsumptionWithoutInvokingMutation() {
        AtomicInteger mutationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        AnalysisRunId runId = new AnalysisRunId("run-1");
        FakeCancellationAdapter cancellation = new FakeCancellationAdapter().requestCancellationAfter(runId, 2);
        AgentActionPort actions = context -> new AgentActionProposal.Proposed(new ExecuteAction(
                ExternalHttpMethod.POST,
                "https://example.invalid/preview",
                Optional.empty(),
                "Preview the requested HTTP mutation"));
        HttpMutationPort mutations = action -> {
            mutationCalls.incrementAndGet();
            return new HttpMutationResult.NotImplemented();
        };

        AgentLoopResult result = loop(actions, mutations, cancellation, transitions).execute(request(runId,
                new AttemptBudget(3, 0, 1, 0, 1, 0, 1, 0, 1, 0)));

        assertThat(result.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(mutationCalls).hasValue(0);
        List<Class<?>> executeEvents = new ArrayList<>();
        for (AgentEvent event : transitions.events()) {
            if (event instanceof AgentEvent.ActionAccepted || event instanceof AgentEvent.ExecuteBudgetConsumed) {
                executeEvents.add(event.getClass());
            }
        }
        assertThat(executeEvents).containsExactly(AgentEvent.ActionAccepted.class, AgentEvent.ExecuteBudgetConsumed.class);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ObservationRecorded.class::isInstance)
                .noneMatch(event -> ((AgentEvent.ObservationRecorded) event).observation().source()
                        == ObservationSource.HTTP_MUTATION);
    }

    @Test
    void rejectsDirectExecuteActionWhenExecuteBudgetWasAlreadyConsumed() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger mutationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ExecuteAction rejectedAction = new ExecuteAction(
                ExternalHttpMethod.POST,
                "https://example.invalid/preview",
                Optional.empty(),
                "Preview the requested HTTP mutation");
        AgentActionPort actions = context -> {
            if (actionCalls.getAndIncrement() == 0) {
                return new AgentActionProposal.Proposed(rejectedAction);
            }
            return new AgentActionProposal.Proposed(new ClarifyAction(
                    "Which environment should receive the change?", List.of(), "Need the target environment"));
        };
        HttpMutationPort mutations = action -> {
            mutationCalls.incrementAndGet();
            return new HttpMutationResult.NotImplemented();
        };

        AgentLoopResult result = loop(actions, mutations, new FakeCancellationAdapter(), transitions).execute(request(
                new AnalysisRunId("run-1"), new AttemptBudget(3, 0, 1, 0, 1, 1, 2, 0, 1, 0)));

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(result.responseKind()).isEqualTo(RunResponseKind.CLARIFICATION);
        assertThat(actionCalls).hasValue(2);
        assertThat(mutationCalls).hasValue(0);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ActionRejected.class::isInstance)
                .singleElement()
                .satisfies(event -> {
                    AgentEvent.ActionRejected rejected = (AgentEvent.ActionRejected) event;
                    assertThat(rejected.originalAction()).contains(rejectedAction);
                    assertThat(rejected.description()).isEqualTo(ActionRejectionCode.EXECUTE_BUDGET_EXHAUSTED.name());
                });
    }

    @Test
    void concludesFailedMutationWhenHttpMutationPortReturnsNull() {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        AgentActionPort actions = context -> new AgentActionProposal.Proposed(executeAction());

        assertThatThrownBy(() -> loop(actions, action -> null, new FakeCancellationAdapter(), transitions)
                .execute(request(new AnalysisRunId("run-1"), executeBudget())))
                .isInstanceOfSatisfying(AnswerExecutionContractException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(AnswerExecutionContractFailure.HTTP_MUTATION_CONTRACT);
                    assertThat(exception).hasMessage("HTTP mutation contract failed");
                });

        assertFailedMutationContract(transitions);
    }

    @Test
    void concludesFailedMutationWhenHttpMutationPortThrowsWithoutExposingItsPayload() {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        String secretUrl = "https://secret.example.invalid/mutate";
        String secretBody = "{\"credential\":\"secret-body\"}";
        AgentActionPort actions = context -> new AgentActionProposal.Proposed(new ExecuteAction(
                ExternalHttpMethod.POST, secretUrl, Optional.of(secretBody), "Preview the requested HTTP mutation"));
        Logger logger = Logger.getLogger(ValidatedAgentLoop.class.getName());
        CapturingLogHandler handler = new CapturingLogHandler();
        logger.addHandler(handler);
        try {
            assertThatThrownBy(() -> loop(actions, action -> {
                throw new IllegalStateException(secretUrl + secretBody);
            }, new FakeCancellationAdapter(), transitions).execute(request(new AnalysisRunId("run-1"), executeBudget())))
                    .isInstanceOfSatisfying(AnswerExecutionContractException.class, exception -> {
                        assertThat(exception.failure()).isEqualTo(AnswerExecutionContractFailure.HTTP_MUTATION_CONTRACT);
                        assertThat(exception).hasMessage("HTTP mutation contract failed");
                        assertThat(exception.getMessage()).doesNotContain(secretUrl, secretBody);
                    });
        } finally {
            logger.removeHandler(handler);
        }

        assertFailedMutationContract(transitions);
        assertThat(handler.messages()).noneMatch(message -> message.contains(secretUrl) || message.contains(secretBody));
    }

    @Test
    void preservesTerminalizationFailureAsThePrimaryContractFailureForAnUnknownMutationOutcome() {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        transitions.failRunConclusion();
        AgentActionPort actions = context -> new AgentActionProposal.Proposed(executeAction());

        assertThatThrownBy(() -> loop(actions, action -> null, new FakeCancellationAdapter(), transitions)
                .execute(request(new AnalysisRunId("run-1"), executeBudget())))
                .isInstanceOfSatisfying(AnswerExecutionContractException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(AnswerExecutionContractFailure.GENERAL_INTEGRATION_CONTRACT);
                    assertThat(exception).hasMessage("integration contract failure could not be concluded");
                    assertThat(exception.getSuppressed()).singleElement().isInstanceOfSatisfying(
                            AnswerExecutionContractException.class,
                            suppressed -> assertThat(suppressed.failure())
                                    .isEqualTo(AnswerExecutionContractFailure.HTTP_MUTATION_CONTRACT));
                });
    }

    private static ValidatedAgentLoop loop(
            AgentActionPort actions,
            HttpMutationPort mutations,
            FakeCancellationAdapter cancellation,
            RecordingTransitionPort transitions) {
        return ValidatedAgentLoop.compose(
                actions,
                invocation -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of()),
                mutations,
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                        AnswerDisposition.ACCEPTED_COMPLETE, List.of(), List.of(), List.of(), List.of())),
                AnswerVerificationMode.LLM,
                new FakeSessionAdapter(),
                new FakeRepositoryCatalogAdapter(new RepositoryDescriptor(new RepositoryId("repo-1"), "Repository one")),
                new FakeCapabilityCatalogAdapter(new CapabilityPolicy(
                        "trace", "v1", Set.of(CandidateKind.REPOSITORY), 1, 1)),
                repository -> RepositoryRevisionResult.ready(new RepositoryRevision("revision-1")),
                cancellation,
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
    }

    private static AgentLoopRequest request(AnalysisRunId runId, AttemptBudget budget) {
        return new AgentLoopRequest(
                runId,
                new SessionId("session-1"),
                new ParticipantRef("test", "participant-1"),
                "Preview this HTTP mutation",
                budget);
    }

    private static ExecuteAction executeAction() {
        return new ExecuteAction(
                ExternalHttpMethod.POST,
                "https://example.invalid/preview",
                Optional.empty(),
                "Preview the requested HTTP mutation");
    }

    private static AttemptBudget executeBudget() {
        return new AttemptBudget(3, 0, 1, 0, 1, 0, 1, 0, 1, 0);
    }

    private static void assertFailedMutationContract(RecordingTransitionPort transitions) {
        AgentRunState persisted = transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow();
        assertThat(persisted.finalOutcome()).contains(RunOutcome.FAILED);
        assertThat(persisted.failureReason()).contains(RunFailureReason.HTTP_MUTATION_CONTRACT);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ObservationRecorded.class::isInstance)
                .noneMatch(event -> ((AgentEvent.ObservationRecorded) event).observation().source()
                        == ObservationSource.HTTP_MUTATION);
    }

    /**
     * 記錄 loop transition 並提供最小 in-memory state 的測試 port
     */
    private static final class RecordingTransitionPort implements AgentTransitionPort {
        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();
        private boolean failRunConclusion;

        @Override
        public AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState state = bootstrap.finalTransition().candidateState();
            states.put(state.runId(), state);
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            return state;
        }

        @Override
        public AgentRunState commit(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (Objects.isNull(existing) || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            if (failRunConclusion && transition.event() instanceof AgentEvent.RunConcluded) {
                throw new AgentTransitionConflictException("terminal conclusion conflict");
            }
            states.put(transition.candidateState().runId(), transition.candidateState());
            events.add(transition.event());
            return transition.candidateState();
        }

        @Override
        public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return commit(transition);
        }

        @Override
        public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private List<AgentEvent> events() {
            return List.copyOf(events);
        }

        private void failRunConclusion() {
            failRunConclusion = true;
        }
    }

    /**
     * 收集測試期間 lifecycle telemetry 的安全記錄內容
     */
    private static final class CapturingLogHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private List<String> messages() {
            return records.stream().map(LogRecord::getMessage).toList();
        }
    }
}
