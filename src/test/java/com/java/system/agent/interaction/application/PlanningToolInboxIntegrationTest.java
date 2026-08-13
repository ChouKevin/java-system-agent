package com.java.system.agent.interaction.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.agent.capability.planning.CanonicalCapabilityPayloadCodec;
import com.java.system.agent.capability.planning.PlanningToolRegistry;
import com.java.system.agent.capability.planning.PlanningToolProvider;
import com.java.system.agent.capability.planning.QueryPlanningMapper;
import com.java.system.agent.capability.planning.QueryPlanningSelection;
import com.java.system.agent.capability.planning.StrictPlanningToolDecoder;
import com.java.system.agent.capability.spi.CapabilityExecutor;
import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxDeferReason;
import com.java.system.agent.interaction.domain.InboxFailure;
import com.java.system.agent.interaction.domain.FinalInteractionResponse;
import com.java.system.agent.interaction.domain.InboxMessage;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.InboxMessageStatus;
import com.java.system.agent.interaction.domain.InboxProcessingOutcome;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.port.out.SessionInboxPort;
import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.application.AnalysisApplicationService;
import com.java.system.agent.answering.application.loop.ContextIssuer;
import com.java.system.agent.answering.application.loop.ValidatedAgentLoop;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.ActionResult;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.ModelInteraction;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.HttpMutationResult;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.in.AnswerQuestionCommand;
import com.java.system.agent.answering.port.in.AnswerQuestionResult;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * planning tool contract defect 跨越 loop 與 inbox 的 terminal failure 整合測試
 */
class PlanningToolInboxIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");
    private static final AttemptBudget BUDGET = new AttemptBudget(2, 0, 1, 0, 1, 0, 2, 0, 1, 0);

    @Test
    void terminally_fails_a_durable_accepted_noncanonical_payload_without_retrying_or_replaying_the_executor() {
        AtomicInteger modelInvocations = new AtomicInteger();
        AtomicInteger executorInvocations = new AtomicInteger();
        RecordingTransitions transitions = new RecordingTransitions();
        PlanningToolRegistry registry = registry(input -> new QueryPlanningSelection<>(
                List.of(), input.questionToResolve(), input.rationale(), new ExecutionInput(input.depth())), executorInvocations);
        ValidatedAgentLoop loop = loop(
                context -> {
                    modelInvocations.incrementAndGet();
                    return new AgentActionProposal.Proposed(new QueryAction(
                            context.issuedCapabilities().keySet().stream().findFirst().orElseThrow(),
                            List.of(), "resolve", new CapabilityInputPayload("{ \"depth\" : 1 }"), "inspect"));
                }, registry::execute, registry, transitions);
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, new AnalysisApplicationService(loop, new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1")), BUDGET, InboxRetryPolicy.defaults());

        InboxProcessingOutcome outcome = processor.process(claim(), NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(modelInvocations).hasValue(1);
        assertThat(executorInvocations).hasValue(0);
        assertThat(inbox.failure).isEqualTo(InboxFailure.PLANNING_TOOL_CONTRACT);
        assertThat(inbox.failedClaim).isEqualTo(claim());
        assertThat(inbox.retriedClaim).isNull();
        assertThat(transitions.state(new AnalysisRunId("run-1")).finalOutcome()).contains(RunOutcome.FAILED);
        assertThat(transitions.events()).anyMatch(AgentEvent.ActionAccepted.class::isInstance);
    }

    @Test
    void terminally_fails_a_planning_mapper_registry_defect_after_one_model_invocation_without_retrying() {
        AtomicInteger modelInvocations = new AtomicInteger();
        AtomicInteger mapperInvocations = new AtomicInteger();
        AtomicInteger executorInvocations = new AtomicInteger();
        RecordingTransitions transitions = new RecordingTransitions();
        PlanningToolRegistry registry = registry(input -> {
            mapperInvocations.incrementAndGet();
            throw new IllegalStateException("mapper wiring is invalid");
        }, executorInvocations);
        ValidatedAgentLoop loop = loop(
                context -> {
                    modelInvocations.incrementAndGet();
                    return registry.interpretToolCall("query_tool", """
                            {"questionToResolve":"resolve","rationale":"inspect","depth":1}
                            """, context);
                }, registry::execute, registry, transitions);
        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, new AnalysisApplicationService(loop, new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1")), BUDGET, InboxRetryPolicy.defaults());

        InboxProcessingOutcome outcome = processor.process(claim(), NOW);

        assertThat(outcome).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(modelInvocations).hasValue(1);
        assertThat(mapperInvocations).hasValue(1);
        assertThat(executorInvocations).hasValue(0);
        assertThat(inbox.failure).isEqualTo(InboxFailure.PLANNING_TOOL_CONTRACT);
        assertThat(inbox.failedClaim).isEqualTo(claim());
        assertThat(inbox.retriedClaim).isNull();
        assertThat(transitions.state(new AnalysisRunId("run-1")).finalOutcome()).contains(RunOutcome.FAILED);
    }

    @Test
    void recovers_a_durable_planning_tool_contract_failure_without_replaying_external_ports() {
        AtomicInteger modelInvocations = new AtomicInteger();
        AtomicInteger executorInvocations = new AtomicInteger();
        RecordingTransitions transitions = new RecordingTransitions();
        PlanningToolRegistry registry = registry(input -> new QueryPlanningSelection<>(
                List.of(), input.questionToResolve(), input.rationale(), new ExecutionInput(input.depth())), executorInvocations);
        ValidatedAgentLoop loop = loop(
                context -> {
                    modelInvocations.incrementAndGet();
                    return new AgentActionProposal.Proposed(new QueryAction(
                            context.issuedCapabilities().keySet().stream().findFirst().orElseThrow(),
                            List.of(), "resolve", new CapabilityInputPayload("{ \"depth\" : 1 }"), "inspect"));
                }, registry::execute, registry, transitions);
        AnalysisApplicationService service = new AnalysisApplicationService(loop, new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"));
        InboxClaim interruptedClaim = claim();

        assertThatThrownBy(() -> service.answer(new AnswerQuestionCommand(
                interruptedClaim.message().runId(), interruptedClaim.message().sessionId(),
                interruptedClaim.message().participant(), interruptedClaim.message().questionText(),
                BUDGET)))
                .isInstanceOf(AnswerExecutionContractException.class)
                .extracting(exception -> ((AnswerExecutionContractException) exception).failure())
                .isEqualTo(AnswerExecutionContractFailure.PLANNING_TOOL_CONTRACT);
        assertThat(transitions.state(new AnalysisRunId("run-1")).finalOutcome()).contains(RunOutcome.FAILED);
        assertThat(modelInvocations).hasValue(1);
        assertThat(executorInvocations).hasValue(0);

        RecordingInboxPort inbox = new RecordingInboxPort();
        SessionInboxProcessor processor = new SessionInboxProcessor(
                inbox, service, BUDGET, InboxRetryPolicy.defaults());
        InboxClaim recoveredClaim = claim(2);

        assertThat(processor.process(recoveredClaim, NOW.plusSeconds(1))).isEqualTo(InboxProcessingOutcome.FAILED);
        assertThat(inbox.failure).isEqualTo(InboxFailure.PLANNING_TOOL_CONTRACT);
        assertThat(inbox.failedClaim).isEqualTo(recoveredClaim);
        assertThat(inbox.retriedClaim).isNull();
        assertThat(modelInvocations).hasValue(1);
        assertThat(executorInvocations).hasValue(0);
    }

    private static ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            CapabilityExecutionPort executionPort,
            PlanningToolRegistry registry,
            RecordingTransitions transitions) {
        return ValidatedAgentLoop.compose(
                withQuestionPlan(actionPort),
                executionPort,
                action -> new HttpMutationResult.NotImplemented(),
                (mode, context) -> { throw new AssertionError("planning contract test must not verify answers"); },
                AnswerVerificationMode.LLM,
                new FakeSessionAdapter(),
                new FakeRepositoryCatalogAdapter(new com.java.system.agent.answering.port.out.RepositoryDescriptor(
                        new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"), "Repository")),
                registry,
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("unused")),
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
    }

    private static AgentActionPort withQuestionPlan(AgentActionPort actions) {
        return context -> questionPlanWasRecorded(context)
                ? actions.nextAction(context)
                : new AgentActionProposal.Proposed(new PlanAction(questionPlan()));
    }

    private static boolean questionPlanWasRecorded(AgentPromptContext context) {
        for (ModelInteraction interaction : context.modelInteractions()) {
            if (interaction instanceof ModelInteraction.ActionResultRecorded recorded
                    && recorded.result() instanceof ActionResult.QuestionPlanRecorded) {
                return true;
            }
        }
        return false;
    }

    private static QuestionPlan questionPlan() {
        return new QuestionPlan(List.of(new InformationNeed(new InformationNeedId("need-1"), "Resolve the request")));
    }

    private static PlanningToolRegistry registry(
            QueryPlanningMapper<PlanningInput, ExecutionInput> mapper,
            AtomicInteger executorInvocations) {
        CapabilityPolicy policy = new CapabilityPolicy("query_tool", "v1", Set.of(CandidateKind.REPOSITORY), 0, 0);
        CapabilityExecutor<ExecutionInput> executor = (context, input) -> {
            executorInvocations.incrementAndGet();
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        };
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        CanonicalCapabilityPayloadCodec payloadCodec = new CanonicalCapabilityPayloadCodec(validator);
        PlanningToolProvider provider = () -> List.of(PlanningToolRegistry.registration(
                policy, PlanningInput.class, ExecutionInput.class, mapper, executor, payloadCodec));
        return new PlanningToolRegistry(List.of(provider),
                new StrictPlanningToolDecoder(validator), payloadCodec);
    }

    private static InboxClaim claim() {
        return claim(1);
    }

    private static InboxClaim claim(int attemptCount) {
        return new InboxClaim(new InboxMessage(
                new InboxMessageId("inbox-1"), new SessionSourceRef("slack", "channel-1:thread-1"),
                new SourceMessageId("message-1"), new SessionId("session-1"), 0, new AnalysisRunId("run-1"),
                new ParticipantRef("slack", "U123456"), "<@bot> question", "question", InboxMessageStatus.PROCESSING,
                attemptCount, NOW, Optional.of(NOW), Optional.<InboxDeferReason>empty(), Optional.empty()));
    }

    private record PlanningInput(
            @JsonProperty(required = true) String questionToResolve,
            @JsonProperty(required = true) String rationale,
            @JsonProperty(required = true) int depth) {
    }

    private record ExecutionInput(@JsonProperty(required = true) int depth) {
    }

    private static final class RecordingTransitions implements AgentTransitionPort {
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();
        private final List<AgentEvent> events = new ArrayList<>();

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
            AgentRunState current = states.get(transition.candidateState().runId());
            if (Objects.isNull(current) || current.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
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

        private AgentRunState state(AnalysisRunId runId) {
            return findByRunId(runId).orElseThrow();
        }

        private List<AgentEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class RecordingInboxPort implements SessionInboxPort {
        private InboxClaim failedClaim;
        private InboxClaim retriedClaim;
        private InboxFailure failure;

        @Override public Optional<InboxClaim> claimNext(Instant now) { throw new UnsupportedOperationException(); }
        @Override public void completeWithFinal(InboxClaim claim, FinalInteractionResponse result, Instant completedAt) { throw new AssertionError(); }
        @Override public void retry(InboxClaim claim, InboxFailure failure, Instant availableAt) { retriedClaim = claim; this.failure = failure; }
        @Override public void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt) { failedClaim = claim; this.failure = failure; }
        @Override public void deferForCapacity(InboxClaim claim, Instant retryAt) { throw new AssertionError(); }
        @Override public int recoverInterrupted(Instant recoveredAt) { throw new UnsupportedOperationException(); }
    }
}
