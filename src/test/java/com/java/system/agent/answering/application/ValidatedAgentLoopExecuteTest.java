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
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
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

import static org.assertj.core.api.Assertions.assertThat;

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
        ValidatedAgentLoop loop = ValidatedAgentLoop.compose(
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
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());

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

    /**
     * 記錄 loop transition 並提供最小 in-memory state 的測試 port
     */
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
