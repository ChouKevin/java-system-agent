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
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.QueryAction;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.capability.CapabilityInputPayload;
import com.java.system.agent.answering.domain.capability.CapabilityPolicy;
import com.java.system.agent.answering.domain.candidate.CandidateKind;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.evidence.ArtifactRef;
import com.java.system.agent.answering.domain.evidence.EvidenceRef;
import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.handle.CandidateHandleRef;
import com.java.system.agent.answering.domain.handle.EvidenceHandleRef;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunAttempt;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.out.AgentActionProposal;
import com.java.system.agent.answering.port.out.AgentActionPort;
import com.java.system.agent.answering.port.out.AgentPromptContext;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionResult;
import com.java.system.agent.answering.port.out.HttpMutationResult;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidatedAgentLoop QUERY 的持久化事件與 attempt 重啟邊界測試
 */
class ValidatedAgentLoopQueryTest {

    private static final AnalysisRunId RUN_ID = new AnalysisRunId("run-1");
    private static final RepositoryId REPOSITORY_ID = new RepositoryId("repo-1");
    private static final ParticipantRef PARTICIPANT = new ParticipantRef("test", "participant-1");
    private static final CapabilityPolicy CAPABILITY = new CapabilityPolicy(
            "trace", "v1", Set.of(CandidateKind.REPOSITORY), 1, 1);

    @Test
    void executesOneQueryAndIssuesItsEvidenceToTheNextModelPrompt() {
        AtomicInteger capabilityCalls = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        EvidenceRef evidence = evidence("rev-1", "query-result");
        ValidatedAgentLoop loop = loop(
                transitions,
                repository -> RepositoryRevisionResult.ready(new RepositoryRevision("rev-1")),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                invocation -> {
                    capabilityCalls.incrementAndGet();
                    return new CapabilityExecutionResult.Succeeded(List.of(), List.of(evidence), List.of());
                },
                context -> nextAction(prompts, context, "Query evidence is available"));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0).issuedCandidates()).hasSize(1);
        assertThat(prompts.get(1).issuedEvidence().values())
                .extracting(issuedEvidence -> issuedEvidence.evidence())
                .containsExactly(evidence);
        List<Class<?>> eventTypes = eventTypes(transitions.events());
        assertThat(eventTypes)
                .containsSubsequence(
                        AgentEvent.ActionAccepted.class,
                        AgentEvent.QueryBudgetConsumed.class,
                        AgentEvent.ContextIssued.class);
    }

    @Test
    void invalidatesThePinnedAttemptBeforeExecutingAQueryAgainstADriftedRevision() {
        AtomicInteger capabilityCalls = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                transitions,
                new SequencedRevisionPort("rev-2"),
                new FakeAttemptIdGenerator()
                        .register(new AnalysisAttemptId("attempt-2")),
                invocation -> {
                    capabilityCalls.incrementAndGet();
                    return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
                },
                context -> nextDriftAction(prompts, context));
        seedPinnedRun(transitions);

        AgentLoopResult result = loop.execute(capacityResumeRequest());

        assertThat(result.outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(capabilityCalls).hasValue(0);
        List<Class<?>> eventTypes = eventTypes(transitions.events());
        assertThat(eventTypes)
                .containsSubsequence(
                        AgentEvent.AttemptInvalidated.class,
                        AgentEvent.AttemptStarted.class);
        assertThat(transitions.state(RUN_ID).attemptSequence()).isEqualTo(2);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1).attemptId()).isEqualTo(new AnalysisAttemptId("attempt-2"));
    }

    private AgentActionProposal nextAction(
            List<AgentPromptContext> prompts,
            AgentPromptContext context,
            String answerText) {
        prompts.add(context);
        if (prompts.size() == 1) {
            return new AgentActionProposal.Proposed(query(context));
        }
        return new AgentActionProposal.Proposed(answer(context, answerText));
    }

    private AgentActionProposal nextDriftAction(List<AgentPromptContext> prompts, AgentPromptContext context) {
        prompts.add(context);
        if (prompts.size() == 1) {
            return new AgentActionProposal.Proposed(query(context));
        }
        return new AgentActionProposal.Proposed(answer(context, "Recovered after revision drift"));
    }

    private QueryAction query(AgentPromptContext context) {
        return new QueryAction(
                context.issuedCapabilities().keySet().iterator().next(),
                List.of(new CandidateHandleRef(context.issuedCandidates().keySet().iterator().next().value())),
                "Trace the repository flow", new CapabilityInputPayload("trace"), "Need repository evidence");
    }

    private AnswerAction answer(AgentPromptContext context, String text) {
        Set<EvidenceHandleRef> evidence = context.issuedEvidence().keySet().stream()
                .map(handle -> new EvidenceHandleRef(handle.value()))
                .collect(Collectors.toUnmodifiableSet());
        return new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, text, Optional.empty(), evidence, Set.of()))));
    }

    private ValidatedAgentLoop loop(
            RecordingTransitionPort transitions,
            RepositoryRevisionPort revisions,
            FakeAttemptIdGenerator attemptIds,
            CapabilityExecutionPort capabilityExecution,
            AgentActionPort actionPort) {
        return ValidatedAgentLoop.compose(
                actionPort,
                capabilityExecution,
                action -> new HttpMutationResult.NotImplemented(),
                (mode, context) -> new AnswerVerificationResult.ContractAccepted(),
                AnswerVerificationMode.CONTRACT_ONLY,
                new FakeSessionAdapter(),
                new FakeRepositoryCatalogAdapter(new RepositoryDescriptor(REPOSITORY_ID, "Repository one")),
                new FakeCapabilityCatalogAdapter(CAPABILITY),
                revisions,
                new FakeCancellationAdapter(),
                attemptIds,
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());
    }

    private AgentLoopRequest request() {
        return new AgentLoopRequest(
                RUN_ID,
                new SessionId("session-1"),
                PARTICIPANT,
                "What does this repository flow do?",
                new AttemptBudget(4, 0, 2, 0, 1, 0, 2, 0, 1, 0));
    }

    private AgentLoopRequest capacityResumeRequest() {
        return new AgentLoopRequest(
                RUN_ID,
                new SessionId("session-1"),
                PARTICIPANT,
                "What does this repository flow do?",
                new AttemptBudget(4, 0, 2, 0, 1, 0, 2, 0, 1, 0),
                AnswerExecutionMode.CAPACITY_RESUME,
                1);
    }

    private void seedPinnedRun(RecordingTransitionPort transitions) {
        AnalysisAttemptId attemptId = new AnalysisAttemptId("attempt-1");
        AttemptBudget budget = new AttemptBudget(4, 0, 2, 0, 1, 0, 2, 0, 1, 0);
        AgentRunState initial = AgentRunState.initial(
                RUN_ID,
                attemptId,
                budget,
                new RunRequestIdentity("session-1", PARTICIPANT, "What does this repository flow do?"));
        RevisionVector revisions = RevisionVector.empty().pin(REPOSITORY_ID, new RepositoryRevision("rev-1"));
        RunAttempt context = new ContextIssuer().issueInitial(
                RUN_ID,
                attemptId,
                revisions,
                List.of(CAPABILITY),
                List.of(new RepositoryDescriptor(REPOSITORY_ID, "Repository one")));
        new AgentTransitionCommitter(new AgentStateReducer(), transitions).bootstrap(initial, context);
    }

    private EvidenceRef evidence(String revision, String digest) {
        return new EvidenceRef(
                "semantic",
                REPOSITORY_ID,
                new RepositoryRevision(revision),
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "The repository calls the order workflow",
                List.of(),
                new ArtifactRef(digest));
    }

    private List<Class<?>> eventTypes(List<AgentEvent> events) {
        List<Class<?>> eventTypes = new ArrayList<>();
        for (AgentEvent event : events) {
            eventTypes.add(event.getClass());
        }
        return List.copyOf(eventTypes);
    }

    private static final class SequencedRevisionPort implements RepositoryRevisionPort {

        private final List<RepositoryRevisionResult> revisions;
        private int index;

        private SequencedRevisionPort(String... revisions) {
            this.revisions = Arrays.stream(revisions)
                    .map(RepositoryRevision::new)
                    .<RepositoryRevisionResult>map(RepositoryRevisionResult::ready)
                    .toList();
        }

        @Override
        public RepositoryRevisionResult currentRevision(RepositoryId repositoryId) {
            Objects.requireNonNull(repositoryId, "repository ID must not be null");
            RepositoryRevisionResult result = revisions.get(index);
            if (index < revisions.size() - 1) {
                index++;
            }
            return result;
        }
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {

        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState state = bootstrap.finalTransition().candidateState();
            states.put(state.runId(), state);
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            return state;
        }

        @Override
        public synchronized AgentRunState commit(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (Objects.isNull(existing) || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            states.put(transition.candidateState().runId(), transition.candidateState());
            events.add(transition.event());
            return transition.candidateState();
        }

        @Override
        public synchronized AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return commit(transition);
        }

        @Override
        public synchronized Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            return Optional.ofNullable(states.get(runId));
        }

        private List<AgentEvent> events() {
            return List.copyOf(events);
        }

        private AgentRunState state(AnalysisRunId runId) {
            return findByRunId(runId).orElseThrow();
        }
    }
}
