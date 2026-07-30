package com.java.system.agent.answering.application;

import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeCapabilityCatalogAdapter;
import com.java.system.agent.answering.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.AgentActionValidator;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.run.RuntimeNoticeReason;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidatedAgentLoop 規劃預算耗盡的 terminal notice 測試
 */
class ValidatedAgentLoopTest {

    @ParameterizedTest
    @MethodSource("exhaustedBudgets")
    void retains_the_exact_exhausted_budget_category_while_returning_the_same_sanitized_notice(
            AttemptBudget budget,
            RuntimeNoticeReason expectedReason) {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        AtomicInteger modelCalls = new AtomicInteger();
        ValidatedAgentLoop loop = loop(transitions, modelCalls);
        AgentLoopRequest request = new AgentLoopRequest(new AnalysisRunId("run-1"), new SessionId("session-1"),
                new ParticipantRef("test", "participant"), "How does this flow work?",
                budget);

        AgentLoopResult result = loop.execute(request);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(result.responseKind()).isEqualTo(RunResponseKind.RUNTIME_NOTICE);
        assertThat(result.responseText()).isEqualTo(ValidatedAgentLoop.PLANNING_BUDGET_EXHAUSTED_RESPONSE);
        assertThat(transitions.events()).filteredOn(AgentEvent.RunConcluded.class::isInstance).singleElement()
                .satisfies(event -> assertThat(((AgentEvent.RunConcluded) event).runtimeNoticeReason())
                        .contains(expectedReason));
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    void exhaustsTheFinalQueryAllowanceBeforeRequestingAnotherModelAction() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger capabilityCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        AgentActionPort actionPort = context -> {
            modelCalls.incrementAndGet();
            return new AgentActionProposal.Proposed(new QueryAction(
                    context.issuedCapabilities().keySet().iterator().next(),
                    List.of(new CandidateHandleRef(context.issuedCandidates().keySet().iterator().next().value())),
                    "Trace the repository flow", new CapabilityInputPayload("trace"), "Need repository evidence"));
        };
        CapabilityExecutionPort capabilityExecution = invocation -> {
            capabilityCalls.incrementAndGet();
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        };
        ValidatedAgentLoop loop = loop(transitions, actionPort, capabilityExecution);
        AgentLoopRequest request = new AgentLoopRequest(new AnalysisRunId("run-1"), new SessionId("session-1"),
                new ParticipantRef("test", "participant"), "How does this flow work?",
                new AttemptBudget(2, 0, 1, 0, 1, 0, 1, 0));

        AgentLoopResult result = loop.execute(request);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(result.responseKind()).isEqualTo(RunResponseKind.RUNTIME_NOTICE);
        assertThat(transitions.events()).filteredOn(AgentEvent.RunConcluded.class::isInstance).singleElement()
                .satisfies(event -> assertThat(((AgentEvent.RunConcluded) event).runtimeNoticeReason())
                        .contains(RuntimeNoticeReason.QUERY_EXECUTION_BUDGET_EXHAUSTED));
        assertThat(capabilityCalls).hasValue(1);
        assertThat(modelCalls).hasValue(1);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> exhaustedBudgets() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        new AttemptBudget(1, 1, 1, 0, 1, 0, 1, 0), RuntimeNoticeReason.AGENT_STEP_BUDGET_EXHAUSTED),
                org.junit.jupiter.params.provider.Arguments.of(
                        new AttemptBudget(1, 0, 1, 1, 1, 0, 1, 0), RuntimeNoticeReason.QUERY_EXECUTION_BUDGET_EXHAUSTED),
                org.junit.jupiter.params.provider.Arguments.of(
                        new AttemptBudget(1, 0, 1, 0, 1, 1, 1, 0), RuntimeNoticeReason.ACTION_REJECTION_BUDGET_EXHAUSTED));
    }

    private static ValidatedAgentLoop loop(RecordingTransitionPort transitions, AtomicInteger modelCalls) {
        return loop(
                transitions,
                context -> {
                    modelCalls.incrementAndGet();
                    throw new AssertionError("exhausted budget must not request an action");
                },
                invocation -> {
                    throw new AssertionError("exhausted budget must not execute a query");
                });
    }

    private static ValidatedAgentLoop loop(
            RecordingTransitionPort transitions,
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecution) {
        CapabilityPolicy policy = new CapabilityPolicy("trace", "v1", Set.of(CandidateKind.REPOSITORY), 1, 2);
        return new ValidatedAgentLoop(
                actionPort,
                capabilityExecution,
                (mode, context) -> new AnswerVerificationResult.LlmVerdict(
                        new AnswerVerdict(AnswerDisposition.ACCEPTED_COMPLETE, List.of(), List.of(), List.of(), List.of())),
                AnswerVerificationMode.LLM,
                new FakeSessionAdapter(),
                new FakeRepositoryCatalogAdapter(new RepositoryDescriptor(new RepositoryId("repo-1"), "Repository one")),
                new FakeCapabilityCatalogAdapter(policy),
                repository -> RepositoryRevisionResult.ready(new RepositoryRevision("rev-1")),
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(), new AnswerDocumentValidator(), new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions), new ContextIssuer());
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {
        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

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
    }
}
