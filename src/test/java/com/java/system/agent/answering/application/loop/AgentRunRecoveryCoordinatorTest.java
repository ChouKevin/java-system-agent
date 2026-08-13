package com.java.system.agent.answering.application.loop;

import com.java.system.agent.answering.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.answering.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.answering.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.answering.application.state.AgentStateReducer;
import com.java.system.agent.answering.application.state.AgentTransitionCommitter;
import com.java.system.agent.answering.application.validation.AnswerDocumentValidator;
import com.java.system.agent.answering.application.validation.AnswerVerdictValidator;
import com.java.system.agent.answering.domain.action.AnswerAction;
import com.java.system.agent.answering.domain.action.PlanAction;
import com.java.system.agent.answering.domain.answer.AnswerDisposition;
import com.java.system.agent.answering.domain.answer.AnswerDocument;
import com.java.system.agent.answering.domain.answer.AnswerStatement;
import com.java.system.agent.answering.domain.answer.AnswerVerdict;
import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.domain.answer.StatementId;
import com.java.system.agent.answering.domain.answer.StatementType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.observation.AgentObservation;
import com.java.system.agent.answering.domain.observation.ObservationCode;
import com.java.system.agent.answering.domain.observation.ObservationId;
import com.java.system.agent.answering.domain.observation.ObservationSource;
import com.java.system.agent.answering.domain.plan.InformationNeed;
import com.java.system.agent.answering.domain.plan.InformationNeedId;
import com.java.system.agent.answering.domain.plan.NeedResolution;
import com.java.system.agent.answering.domain.plan.NeedResolutionStatus;
import com.java.system.agent.answering.domain.plan.QuestionPlan;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentRunStatus;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.AttemptBudget;
import com.java.system.agent.answering.domain.run.PendingAnswerVerification;
import com.java.system.agent.answering.domain.run.RunFailureReason;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.domain.scope.RepositoryId;
import com.java.system.agent.answering.domain.scope.RepositoryRevision;
import com.java.system.agent.answering.domain.scope.RevisionVector;
import com.java.system.agent.answering.port.in.AnswerExecutionContractException;
import com.java.system.agent.answering.port.in.AnswerExecutionContractFailure;
import com.java.system.agent.answering.port.in.AnswerExecutionMode;
import com.java.system.agent.answering.port.in.AnswerExecutionUnavailableException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationUnavailableException;
import com.java.system.agent.answering.port.out.CapabilityCatalogPort;
import com.java.system.agent.answering.port.out.CapabilityExecutionPort;
import com.java.system.agent.answering.port.out.HttpMutationPort;
import com.java.system.agent.answering.port.out.HttpMutationResult;
import com.java.system.agent.answering.port.out.RepositoryCatalogPort;
import com.java.system.agent.answering.port.out.RepositoryDescriptor;
import com.java.system.agent.answering.port.out.RepositoryRevisionPort;
import com.java.system.agent.answering.port.out.RepositoryRevisionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.RecordComponent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentRunRecoveryCoordinator 的 durable state dispatch 邊界測試
 */
class AgentRunRecoveryCoordinatorTest {

    @Test
    void agentLoopRequestCarriesTheRuntimeOwnedRepositoryId() {
        List<String> componentNames = java.util.Arrays.stream(AgentLoopRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).contains("repositoryId");
    }

    @Test
    void runRequestIdentityPersistsTheRuntimeOwnedRepositoryId() {
        List<String> componentNames = java.util.Arrays.stream(RunRequestIdentity.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).contains("repositoryId");
    }

    @Test
    void initialWithoutPersistedStateBootstrapsAnActiveExecution() {
        Fixture fixture = fixture((mode, context) -> accepted());

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.initialRequest());

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Active.class);
        assertThat(fixture.port().events()).filteredOn(AgentEvent.RunStarted.class::isInstance).hasSize(1);
    }

    @Test
    void bootstrapPinsTheConfiguredRepositoryRevisionBeforeIssuingContext() {
        Fixture fixture = fixture((mode, context) -> accepted());

        AgentRunRecoveryOutcome.Active outcome = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.initialRequest());

        assertThat(outcome.execution().state().requestIdentity().repositoryId()).isEqualTo(new RepositoryId("repo-1"));
        assertThat(outcome.execution().state().currentAttempt().revisionVector().entries())
                .containsExactly(new RevisionVector.Entry(
                        new RepositoryId("repo-1"), new RepositoryRevision("revision-1")));
    }

    @Test
    void bootstrapSelectsTheConfiguredRepositoryFromTheCatalogAndPinsItsRevision() {
        RepositoryId repositoryA = new RepositoryId("repository-a");
        RepositoryId repositoryB = new RepositoryId("repository-b");
        Fixture fixture = fixture(
                (mode, context) -> accepted(),
                List.of(
                        new RepositoryDescriptor(repositoryA, "Repository A"),
                        new RepositoryDescriptor(repositoryB, "Repository B")),
                repositoryId -> {
                    assertThat(repositoryId).isEqualTo(repositoryB);
                    return RepositoryRevisionResult.ready(new RepositoryRevision("revision-b"));
                });
        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                "What is verified?", repositoryB,
                new AttemptBudget(2, 0, 1, 0, 1, 0, 2, 0, 1, 0));

        AgentRunRecoveryOutcome.Active outcome = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(request);

        assertThat(outcome.execution().state().requestIdentity().repositoryId()).isEqualTo(repositoryB);
        assertThat(outcome.execution().state().currentAttempt().revisionVector().entries())
                .containsExactly(new RevisionVector.Entry(repositoryB, new RepositoryRevision("revision-b")));
    }

    @Test
    void capacityResumeReturnsPersistedRunningExecutionWithoutReloadingCatalogs() {
        Fixture fixture = fixture((mode, context) -> accepted());
        fixture.coordinator().recover(fixture.initialRequest());
        int catalogReads = fixture.catalogReads().get();

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.request(AnswerExecutionMode.CAPACITY_RESUME, 1));

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Active.class);
        assertThat(fixture.catalogReads()).hasValue(catalogReads);
    }

    @ParameterizedTest
    @EnumSource(value = AnswerExecutionMode.class, names = {"RETRY", "CAPACITY_RESUME"})
    void retryAndCapacityResumeRejectAChangedRepositoryIdentityBeforeProviderWork(AnswerExecutionMode mode) {
        Fixture fixture = fixture((verificationMode, context) -> accepted());
        fixture.coordinator().recover(fixture.initialRequest());
        int catalogReads = fixture.catalogReads().get();
        int revisionReads = fixture.revisionReads().get();

        AgentLoopRequest changedScope = new AgentLoopRequest(
                new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                "What is verified?", new RepositoryId("repo-2"),
                new AttemptBudget(2, 0, 1, 0, 1, 0, 2, 0, 1, 0), mode, mode == AnswerExecutionMode.RETRY ? 2 : 1);

        assertThatThrownBy(() -> fixture.coordinator().recover(changedScope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("incoming request does not match the persisted request identity");
        assertThat(fixture.catalogReads()).hasValue(catalogReads);
        assertThat(fixture.revisionReads()).hasValue(revisionReads);
    }

    @Test
    void pendingVerificationCallsOnlyTheVerifierAndDoesNotRestartTheAttempt() {
        AtomicInteger verifications = new AtomicInteger();
        AtomicReference<AnswerVerificationContext> verificationContext = new AtomicReference<>();
        Fixture fixture = fixture((mode, context) -> {
            verificationContext.set(context);
            if (verifications.incrementAndGet() == 1) {
                throw new AnswerVerificationUnavailableException("temporary outage", null); // cs-allow
            }
            return accepted();
        });
        AgentRunRecoveryOutcome.Active active = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.initialRequest());
        QuestionPlan plan = plan();
        AgentStateReducer reducer = new AgentStateReducer();
        AgentRunState planSelectedState = fixture.port().commit(reducer.reduce(active.execution().state(),
                new AgentEvent.ActionSelected(
                        active.execution().state().runId(),
                        active.execution().state().currentAttempt().attemptId(),
                        active.execution().state().stateRevision(),
                        new PlanAction(plan))));
        AgentRunState planRecordedState = fixture.port().commit(reducer.reduce(planSelectedState,
                new AgentEvent.QuestionPlanCreated(
                        planSelectedState.runId(),
                        planSelectedState.currentAttempt().attemptId(),
                        planSelectedState.stateRevision(),
                        plan)));
        AgentRunState observedState = recordResolutionObservations(fixture.port(), reducer, planRecordedState);
        AnswerAction proposedAnswer = answer();
        AgentRunState selectedState = fixture.port().commit(reducer.reduce(observedState,
                new AgentEvent.ActionSelected(
                        observedState.runId(),
                        observedState.currentAttempt().attemptId(),
                        observedState.stateRevision(),
                        proposedAnswer)));
        assertThatThrownBy(() -> fixture.answerExecutor().execute(
                fixture.initialRequest(), fixture.session().read(fixture.initialRequest().sessionId()), selectedState,
                proposedAnswer, 1))
                .isInstanceOf(AnswerExecutionUnavailableException.class);

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.request(AnswerExecutionMode.RETRY, 2));

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Terminal.class);
        assertThat(verifications).hasValue(2);
        assertThat(verificationContext).hasValueSatisfying(context -> {
            assertThat(context.questionPlan()).isEqualTo(plan);
            assertThat(context.needResolutions()).isEqualTo(resolutions());
        });
        assertThat(fixture.mutationCalls()).hasValue(0);
        assertThat(fixture.port().events()).filteredOn(AgentEvent.AttemptInvalidated.class::isInstance).isEmpty();
        assertThat(fixture.port().events()).filteredOn(AgentEvent.ActionSelected.class::isInstance).hasSize(2);
    }

    @Test
    void recoveryRejectsTamperedPendingResolutionsBeforeCallingTheVerifier() {
        AtomicInteger verifications = new AtomicInteger();
        Fixture fixture = fixture((mode, context) -> {
            verifications.incrementAndGet();
            return accepted();
        });
        AgentRunRecoveryOutcome.Active active = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.initialRequest());
        AnswerAction tamperedAnswer = new AnswerAction(answer().document(), List.of(
                unavailable("need-2", "attempt-1:O1"),
                unavailable("need-1", "attempt-1:O2")));
        pendingVerification(fixture.port(), active.execution().state(), plan(), tamperedAnswer);

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.request(AnswerExecutionMode.RETRY, 2));

        assertTerminalIntegrationFailure(outcome, fixture);
        assertThat(verifications).hasValue(0);
    }

    @Test
    void recoveryConcludesPendingVerificationWithoutAPersistedPlan() {
        AtomicInteger verifications = new AtomicInteger();
        Fixture fixture = fixture((mode, context) -> {
            verifications.incrementAndGet();
            return accepted();
        });
        AgentRunRecoveryOutcome.Active active = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.initialRequest());
        AgentStateReducer reducer = new AgentStateReducer();
        AnswerAction answer = answer();
        AgentRunState selectedState = fixture.port().commit(reducer.reduce(active.execution().state(),
                new AgentEvent.ActionSelected(
                        active.execution().state().runId(),
                        active.execution().state().currentAttempt().attemptId(),
                        active.execution().state().stateRevision(),
                        answer)));
        fixture.port().commit(reducer.reduce(selectedState, new AgentEvent.AnswerProposed(
                selectedState.runId(), selectedState.currentAttempt().attemptId(), selectedState.stateRevision(),
                new PendingAnswerVerification(
                        selectedState.currentAttempt().attemptId(), selectedState.currentAttempt().revisionVector(),
                        answer, AnswerVerificationMode.LLM))));

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(fixture.request(AnswerExecutionMode.RETRY, 2));

        assertTerminalIntegrationFailure(outcome, fixture);
        assertThat(verifications).hasValue(0);
    }

    @Test
    void terminalReconciliationDoesNotCallExternalExecutionPorts() {
        Fixture fixture = fixture((mode, context) -> {
            throw new AssertionError("terminal reconciliation must not verify");
        });
        fixture.coordinator().recover(fixture.initialRequest());
        int catalogReads = fixture.catalogReads().get();

        AgentRunRecoveryOutcome outcome = fixture.coordinator().recover(
                fixture.request(AnswerExecutionMode.TERMINAL_RECONCILIATION, 4));

        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Terminal.class);
        assertThat(fixture.catalogReads()).hasValue(catalogReads);
        assertThat(fixture.mutationCalls()).hasValue(0);
    }

    @Test
    void rethrowsRecoveredHttpMutationContractFailureWithoutCallingTheMutationPort() {
        Fixture fixture = fixture((mode, context) -> accepted());
        fixture.coordinator().recover(fixture.initialRequest());
        fixture.port().concludeWithFailureReason(RunFailureReason.HTTP_MUTATION_CONTRACT);

        assertThatThrownBy(() -> fixture.coordinator().recover(fixture.request(AnswerExecutionMode.RETRY, 2)))
                .isInstanceOfSatisfying(AnswerExecutionContractException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(AnswerExecutionContractFailure.HTTP_MUTATION_CONTRACT);
                    assertThat(exception).hasMessage("HTTP mutation contract failed");
                });

        assertThat(fixture.mutationCalls()).hasValue(0);
    }

    @Test
    void retry_closes_an_uncommitted_selected_plan_without_persisting_a_plan() {
        Fixture fixture = fixture((mode, context) -> accepted());
        AgentRunRecoveryOutcome.Active active = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.initialRequest());
        PlanAction action = new PlanAction(plan());
        AgentRunState selected = fixture.port().commit(new AgentStateReducer().reduce(active.execution().state(),
                new AgentEvent.ActionSelected(
                        active.execution().state().runId(), active.execution().state().currentAttempt().attemptId(),
                        active.execution().state().stateRevision(), action)));

        AgentRunRecoveryOutcome.Active recovered = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.request(AnswerExecutionMode.RETRY, 2));

        assertThat(recovered.execution().state().questionPlan()).isEmpty();
        assertThat(recovered.execution().state().unresolvedSelectedAction()).isEmpty();
        assertThat(fixture.port().events()).contains(new AgentEvent.ActionResultRecorded(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(),
                new com.java.system.agent.answering.domain.run.ActionResult.ActionInterrupted(
                        "RECOVERY_INTERRUPTED", "Selected action outcome was not durably known when execution resumed")));
    }

    @Test
    void retry_preserves_an_exact_committed_question_plan() {
        Fixture fixture = fixture((mode, context) -> accepted());
        AgentRunRecoveryOutcome.Active active = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.initialRequest());
        QuestionPlan plan = plan();
        PlanAction action = new PlanAction(plan);
        AgentRunState selected = fixture.port().commit(new AgentStateReducer().reduce(active.execution().state(),
                new AgentEvent.ActionSelected(
                        active.execution().state().runId(), active.execution().state().currentAttempt().attemptId(),
                        active.execution().state().stateRevision(), action)));
        fixture.port().commit(new AgentStateReducer().reduce(selected, new AgentEvent.QuestionPlanCreated(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(), plan)));

        AgentRunRecoveryOutcome.Active recovered = (AgentRunRecoveryOutcome.Active) fixture.coordinator().recover(
                fixture.request(AnswerExecutionMode.RETRY, 2));

        assertThat(recovered.execution().state().questionPlan()).contains(plan);
    }

    private static Fixture fixture(AnswerVerificationPort verifier) {
        return fixture(
                verifier,
                List.of(new RepositoryDescriptor(new RepositoryId("repo-1"), "Repository one")),
                repositoryId -> {
                    if (!repositoryId.equals(new RepositoryId("repo-1"))) {
                        throw new AssertionError("recovery must resolve only the configured repository");
                    }
                    return RepositoryRevisionResult.ready(new RepositoryRevision("revision-1"));
                });
    }

    private static Fixture fixture(
            AnswerVerificationPort verifier,
            List<RepositoryDescriptor> repositoryCatalog,
            RepositoryRevisionPort repositoryRevisionPort) {
        RecordingTransitionPort port = new RecordingTransitionPort();
        FakeSessionAdapter session = new FakeSessionAdapter();
        AtomicInteger catalogReads = new AtomicInteger();
        AgentRunTransitions transitions = new AgentRunTransitions(new AgentTransitionCommitter(new AgentStateReducer(), port));
        CapabilityCatalogPort capabilities = () -> {
            catalogReads.incrementAndGet();
            return List.of();
        };
        RepositoryCatalogPort repositories = () -> {
            catalogReads.incrementAndGet();
            return repositoryCatalog;
        };
        CapabilityExecutionPort execution = invocation -> {
            throw new AssertionError("recovery test must not execute capabilities");
        };
        AtomicInteger revisionReads = new AtomicInteger();
        RepositoryRevisionPort revisions = repositoryId -> {
            revisionReads.incrementAndGet();
            return repositoryRevisionPort.currentRevision(repositoryId);
        };
        AtomicInteger mutationCalls = new AtomicInteger();
        HttpMutationPort mutations = action -> {
            mutationCalls.incrementAndGet();
            return new HttpMutationResult.NotImplemented();
        };
        AgentLoopTelemetry telemetry = new AgentLoopTelemetry(
                execution,
                mutations,
                verifier,
                capabilities,
                repositories,
                revisions);
        TerminalResponseCoordinator terminal = new TerminalResponseCoordinator(transitions, session);
        AnswerActionExecutor answerExecutor = new AnswerActionExecutor(
                telemetry, new FakeCancellationAdapter(), AnswerVerificationMode.LLM,
                new AnswerDocumentValidator(), new AnswerVerdictValidator(), transitions, terminal);
        AgentRunRecoveryCoordinator coordinator = new AgentRunRecoveryCoordinator(
                transitions,
                telemetry,
                new ContextIssuer(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1"), new AnalysisAttemptId("attempt-2")),
                session,
                answerExecutor,
                terminal);
        return new Fixture(coordinator, answerExecutor, session, port, catalogReads, revisionReads, mutationCalls);
    }

    private static AnswerVerificationResult accepted() {
        return new AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                AnswerDisposition.ACCEPTED_COMPLETE, List.of(), List.of(), List.of(), List.of()));
    }

    private AgentLoopRequest initialRequest() {
        return request(AnswerExecutionMode.INITIAL, 1);
    }

    private AgentLoopRequest request(AnswerExecutionMode mode, int attempt) {
        return new AgentLoopRequest(
                new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                "What is verified?", new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"), new AttemptBudget(2, 0, 1, 0, 1, 0, 2, 0, 1, 0), mode, attempt);
    }

    private static AnswerAction answer() {
        return new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, "Verified answer", Optional.empty(), Set.of(), Set.of()))),
                resolutions());
    }

    private static QuestionPlan plan() {
        return new QuestionPlan(List.of(
                new InformationNeed(new InformationNeedId("need-1"), "Trace the route"),
                new InformationNeed(new InformationNeedId("need-2"), "Identify the limitation")));
    }

    private static List<NeedResolution> resolutions() {
        return List.of(unavailable("need-1", "attempt-1:O1"), unavailable("need-2", "attempt-1:O2"));
    }

    private static NeedResolution unavailable(String needId, String observationId) {
        return new NeedResolution(new InformationNeedId(needId), NeedResolutionStatus.UNAVAILABLE,
                Set.of(), Set.of(new ObservationId(observationId)));
    }

    private static AgentRunState recordResolutionObservations(
            RecordingTransitionPort port,
            AgentStateReducer reducer,
            AgentRunState initialState) {
        AgentRunState first = port.commit(reducer.reduce(initialState, new AgentEvent.ObservationRecorded(
                initialState.runId(), initialState.currentAttempt().attemptId(), initialState.stateRevision(),
                observation("attempt-1:O1", "The route could not be resolved"))));
        return port.commit(reducer.reduce(first, new AgentEvent.ObservationRecorded(
                first.runId(), first.currentAttempt().attemptId(), first.stateRevision(),
                observation("attempt-1:O2", "The limitation could not be resolved"))));
    }

    private static AgentObservation observation(String observationId, String description) {
        return new AgentObservation(new ObservationId(observationId), ObservationSource.RUNTIME,
                ObservationCode.UNADDRESSED_PART, description, Set.of(), Set.of(), "runtime");
    }

    private static void pendingVerification(
            RecordingTransitionPort port,
            AgentRunState initialState,
            QuestionPlan plan,
            AnswerAction answer) {
        AgentStateReducer reducer = new AgentStateReducer();
        AgentRunState planSelected = port.commit(reducer.reduce(initialState, new AgentEvent.ActionSelected(
                initialState.runId(), initialState.currentAttempt().attemptId(), initialState.stateRevision(),
                new PlanAction(plan))));
        AgentRunState planRecorded = port.commit(reducer.reduce(planSelected, new AgentEvent.QuestionPlanCreated(
                planSelected.runId(), planSelected.currentAttempt().attemptId(), planSelected.stateRevision(), plan)));
        AgentRunState observed = recordResolutionObservations(port, reducer, planRecorded);
        AgentRunState selected = port.commit(reducer.reduce(observed, new AgentEvent.ActionSelected(
                observed.runId(), observed.currentAttempt().attemptId(), observed.stateRevision(), answer)));
        port.commit(reducer.reduce(selected, new AgentEvent.AnswerProposed(
                selected.runId(), selected.currentAttempt().attemptId(), selected.stateRevision(),
                new PendingAnswerVerification(
                        selected.currentAttempt().attemptId(), selected.currentAttempt().revisionVector(),
                        answer, AnswerVerificationMode.LLM))));
    }

    private static void assertTerminalIntegrationFailure(AgentRunRecoveryOutcome outcome, Fixture fixture) {
        assertThat(outcome).isInstanceOf(AgentRunRecoveryOutcome.Terminal.class);
        assertThat(((AgentRunRecoveryOutcome.Terminal) outcome).result().responseText())
                .isEqualTo(TerminalResponseCoordinator.FAILED_RESPONSE);
        AgentRunState concluded = fixture.port().findByRunId(new AnalysisRunId("run-1")).orElseThrow();
        assertThat(concluded.status()).isEqualTo(AgentRunStatus.CONCLUDED);
        assertThat(concluded.finalOutcome()).contains(RunOutcome.FAILED);
        assertThat(concluded.pendingAnswerVerification()).isEmpty();
    }

    private record Fixture(
            AgentRunRecoveryCoordinator coordinator,
            AnswerActionExecutor answerExecutor,
            FakeSessionAdapter session,
            RecordingTransitionPort port,
            AtomicInteger catalogReads,
            AtomicInteger revisionReads,
            AtomicInteger mutationCalls) {

        private AgentLoopRequest initialRequest() {
            return new AgentLoopRequest(
                    new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                    "What is verified?", new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"), new AttemptBudget(2, 0, 1, 0, 1, 0, 2, 0, 1, 0));
        }

        private AgentLoopRequest request(AnswerExecutionMode mode, int attempt) {
            return new AgentLoopRequest(
                    new AnalysisRunId("run-1"), new SessionId("session-1"), new ParticipantRef("test", "participant-1"),
                    "What is verified?", new com.java.system.agent.answering.domain.scope.RepositoryId("repo-1"), new AttemptBudget(2, 0, 1, 0, 1, 0, 2, 0, 1, 0), mode, attempt);
        }
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {
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

        private void concludeWithFailureReason(RunFailureReason failureReason) {
            AgentRunState current = states.values().stream().findFirst().orElseThrow();
            AgentTransition conclusion = new AgentStateReducer().reduce(current, new AgentEvent.RunConcluded(
                    current.runId(),
                    current.currentAttempt().attemptId(),
                    current.stateRevision(),
                    RunOutcome.FAILED,
                    Optional.empty(),
                    Optional.of(failureReason)));
            commit(conclusion);
        }
    }
}
