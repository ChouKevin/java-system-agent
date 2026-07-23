package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisOutcome;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.ArtifactRef;
import com.java.system.agent.analysis.domain.AttemptOutcome;
import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.Goal;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.InformationNeedType;
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RepositorySelection;
import com.java.system.agent.analysis.domain.RevisionVector;
import com.java.system.agent.analysis.domain.SemanticTarget;
import com.java.system.agent.analysis.domain.SemanticTargetKind;
import com.java.system.agent.analysis.port.in.AnalysisExecutionCommand;
import com.java.system.agent.analysis.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.analysis.port.out.RepositoryRevisionPort;
import com.java.system.agent.analysis.port.out.RepositoryRevisionResult;
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;
import com.java.system.agent.analysis.port.out.SemanticFailure;
import com.java.system.agent.analysis.port.out.SemanticFailureCode;
import com.java.system.agent.runtime.adapter.fake.InMemoryAnalysisTransitionAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptLifecycleManagerTest {

    @Test
    void startsByCommittingScopeBeforeOrderedProbePinsAndNeedRegistration() {
        RepositoryId alpha = repository("alpha-service");
        RepositoryId zeta = repository("zeta-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(alpha, ready("alpha-1"))
                .register(zeta, ready("zeta-1"));
        Fixture fixture = fixture(revisions);
        InformationNeed zetaNeed = need("zeta-need", zeta);
        InformationNeed alphaNeed = need("alpha-need", alpha);

        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(zeta, alpha), List.of(zetaNeed, alphaNeed)));

        assertThat(revisions.calls()).containsExactly(alpha, zeta);
        assertThat(lifecycle.run().currentAttempt().id()).isEqualTo(new AnalysisAttemptId("attempt-1"));
        assertThat(lifecycle.run().currentAttempt().revisionVector()).isEqualTo(RevisionVector.empty());
        assertThat(lifecycle.run().currentAttempt().budget()).isEqualTo(AnalysisBudget.of(10, 5));
        assertThat(lifecycle.state().revisionVector().repositoryIds()).containsExactly(alpha, zeta);
        assertThat(lifecycle.state().budget().usedSteps()).isEqualTo(2);
        assertThat(lifecycle.revisionRestartCount()).isZero();
        assertEventOrder(fixture.transitions().events(),
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class,
                AnalysisEvent.RevisionPinned.class,
                AnalysisEvent.BudgetConsumed.class,
                AnalysisEvent.RevisionPinned.class,
                AnalysisEvent.NeedRegistered.class,
                AnalysisEvent.NeedRegistered.class);
        assertThat(fixture.transitions().events())
                .filteredOn(AnalysisEvent.BudgetConsumed.class::isInstance)
                .allSatisfy(event -> assertThat(((AnalysisEvent.BudgetConsumed) event).activity())
                        .isEqualTo(AnalysisBudgetActivity.REVISION_PROBE));
        assertThat(fixture.transitions().events())
                .filteredOn(AnalysisEvent.RevisionPinned.class::isInstance)
                .extracting(event -> ((AnalysisEvent.RevisionPinned) event).repositoryId())
                .containsExactly(alpha, zeta);
        assertThat(fixture.transitions().events())
                .filteredOn(AnalysisEvent.NeedRegistered.class::isInstance)
                .extracting(event -> ((AnalysisEvent.NeedRegistered) event).informationNeed().id())
                .containsExactly(alphaNeed.id(), zetaNeed.id());
    }

    @Test
    void exposesUnavailableRevisionAsTypedPreparationFailureWithoutInventingRevision() {
        RepositoryId repositoryId = repository("order-service");
        SemanticFailure failure = new SemanticFailure(
                SemanticFailureCode.NOT_READY, "  revision source is warming up  ", true);
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, RepositoryRevisionResult.unavailable(failure));
        Fixture fixture = fixture(revisions);

        assertThatThrownBy(() -> fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)))))
                .isInstanceOfSatisfying(AttemptPreparationException.class, exception -> {
                    assertThat(exception.semanticFailure()).isEqualTo(failure);
                    assertThat(exception.lastCommittedLifecycle().state().revisionVector().repositoryIds())
                            .isEmpty();
                    assertThat(exception.lastCommittedLifecycle().state().budget().usedSteps()).isEqualTo(1);
                    assertThat(exception.lastCommittedLifecycle().state().status())
                            .isEqualTo(AnalysisStatus.REVISION_PINNING);
                });
        assertEventOrder(fixture.transitions().events(),
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class);
    }

    @Test
    void exposesAllRevisionUnavailableFailureCodesAsTypedPreparationFailures() {
        for (SemanticFailureCode code : List.of(
                SemanticFailureCode.NOT_READY,
                SemanticFailureCode.FORBIDDEN,
                SemanticFailureCode.REPOSITORY_NOT_FOUND)) {
            RepositoryId repositoryId = repository("service-" + code.name().toLowerCase());
            SemanticFailure failure = new SemanticFailure(code, "revision unavailable", false);
            Fixture fixture = fixture(new RecordingRepositoryRevisionPort()
                    .register(repositoryId, RepositoryRevisionResult.unavailable(failure)));

            assertThatThrownBy(() -> fixture.manager().start(command(
                    "run-" + code.name(),
                    "attempt-1",
                    List.of(repositoryId),
                    List.of(need("need-" + code.name(), repositoryId)))))
                    .isInstanceOfSatisfying(AttemptPreparationException.class, exception ->
                            assertThat(exception.semanticFailure().code()).isEqualTo(code));
        }
    }

    @Test
    void restartsOnceWithAStaleOldAttemptFreshStateAndFullPriorScope() {
        RepositoryId initialRepository = repository("order-service");
        RepositoryId discoveredRepository = repository("notification-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(initialRepository, ready("order-1"), ready("order-2"))
                .register(discoveredRepository, ready("notification-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator(new AnalysisAttemptId("attempt-2"));
        Fixture fixture = fixture(revisions, attemptIds);
        InformationNeed informationNeed = need("need-1", initialRepository);
        AttemptLifecycle initial = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(initialRepository), List.of(informationNeed)));
        AttemptLifecycle withEvidenceAndDiscovery = lifecycleWithEvidenceAndDiscoveredScope(
                fixture, initial, discoveredRepository);

        AttemptLifecycle restarted = fixture.manager().restartAfterRevisionMismatch(
                withEvidenceAndDiscovery,
                command("run-1", "attempt-1", List.of(initialRepository), List.of(informationNeed)));

        assertThat(restarted.revisionRestartCount()).isEqualTo(1);
        assertThat(restarted.run().attempts()).hasSize(2);
        assertThat(restarted.run().attempts().getFirst().outcome()).contains(AttemptOutcome.STALE);
        assertThat(restarted.run().currentAttempt().id()).isEqualTo(new AnalysisAttemptId("attempt-2"));
        assertThat(restarted.run().currentAttempt().revisionVector()).isEqualTo(RevisionVector.empty());
        assertThat(restarted.run().currentAttempt().budget()).isEqualTo(AnalysisBudget.of(10, 5));
        assertThat(attemptIds.calls()).containsExactly(new AttemptIdCall(new AnalysisRunId("run-1"), 2));
        assertThat(restarted.state().repositoryScope().repositoryIds())
                .containsExactly(discoveredRepository, initialRepository);
        assertThat(restarted.state().revisionVector().repositoryIds())
                .containsExactly(discoveredRepository, initialRepository);
        assertThat(restarted.state().pendingNeeds()).containsOnlyKeys(informationNeed.id());
        assertThat(restarted.state().resolvedNeedIds()).isEmpty();
        assertThat(restarted.state().evidenceBindings()).isEmpty();
        assertThat(restarted.state().warnings()).isEmpty();
        assertThat(restarted.state().budget()).isEqualTo(new AnalysisBudget(10, 2, 5, 0));
        assertThat(revisions.calls()).containsExactly(
                initialRepository,
                discoveredRepository,
                initialRepository);
        assertThat(fixture.transitions().events())
                .filteredOn(AnalysisEvent.AttemptConcluded.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AnalysisEvent.AttemptConcluded) event).outcome())
                        .isEqualTo(AttemptOutcome.STALE));
    }

    @Test
    void rejectsSecondRestartBeforeTransitionOrAttemptIdSideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"), ready("order-2"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator(new AnalysisAttemptId("attempt-2"));
        Fixture fixture = fixture(revisions, attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle restarted = fixture.manager().restartAfterRevisionMismatch(
                fixture.manager().start(command), command);
        long eventsBeforeRejectedRestart = fixture.transitions().events().size();
        int callsBeforeRejectedRestart = attemptIds.calls().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(restarted, command))
                .withMessageContaining("already been restarted");

        assertThat(fixture.transitions().events()).hasSize((int) eventsBeforeRejectedRestart);
        assertThat(attemptIds.calls()).hasSize(callsBeforeRejectedRestart);
    }

    @Test
    void rejectsRestartForACommandWithAnotherFirstAttemptBeforeAnySideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1")), attemptIds);
        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId))));
        int eventsBeforeRejectedRestart = fixture.transitions().events().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        lifecycle,
                        command("run-1", "other-attempt", List.of(repositoryId),
                                List.of(need("need-1", repositoryId)))))
                .withMessageContaining("first attempt");

        assertThat(fixture.transitions().events()).hasSize(eventsBeforeRejectedRestart);
        assertThat(attemptIds.calls()).isEmpty();
    }

    @Test
    void pinsDiscoveredRepositoryOnceAndReturnsSameLifecycleWhenAlreadyPinned() {
        RepositoryId initialRepository = repository("order-service");
        RepositoryId discoveredRepository = repository("notification-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(initialRepository, ready("order-1"))
                .register(discoveredRepository, ready("notification-1"));
        Fixture fixture = fixture(revisions);
        AttemptLifecycle lifecycle = lifecycleWithEvidenceAndDiscoveredScope(
                fixture,
                fixture.manager().start(command(
                        "run-1", "attempt-1", List.of(initialRepository),
                        List.of(need("need-1", initialRepository)))),
                discoveredRepository);
        int eventsBeforePin = fixture.transitions().events().size();

        AttemptLifecycle pinned = fixture.manager().pinDiscoveredRepository(lifecycle, discoveredRepository);
        AttemptLifecycle repeated = fixture.manager().pinDiscoveredRepository(pinned, discoveredRepository);

        assertThat(pinned.state().revisionVector().revisionOf(discoveredRepository))
                .contains(new RepositoryRevision("notification-1"));
        assertThat(repeated).isSameAs(pinned);
        assertThat(revisions.calls()).containsExactly(initialRepository, discoveredRepository);
        assertThat(fixture.transitions().events()).hasSize(eventsBeforePin + 2);
        assertEventOrder(fixture.transitions().events().subList(eventsBeforePin, eventsBeforePin + 2),
                AnalysisEvent.BudgetConsumed.class,
                AnalysisEvent.RevisionPinned.class);
    }

    @Test
    void retainsProbeBudgetAndTypedFailureWhenDiscoveredRepositoryIsUnavailable() {
        RepositoryId initialRepository = repository("order-service");
        RepositoryId discoveredRepository = repository("notification-service");
        SemanticFailure failure = new SemanticFailure(
                SemanticFailureCode.FORBIDDEN, "repository access denied", false);
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(initialRepository, ready("order-1"))
                .register(discoveredRepository, RepositoryRevisionResult.unavailable(failure));
        Fixture fixture = fixture(revisions);
        AttemptLifecycle lifecycle = lifecycleWithEvidenceAndDiscoveredScope(
                fixture,
                fixture.manager().start(command(
                        "run-1", "attempt-1", List.of(initialRepository),
                        List.of(need("need-1", initialRepository)))),
                discoveredRepository);
        int eventsBeforeProbe = fixture.transitions().events().size();

        assertThatThrownBy(() -> fixture.manager().pinDiscoveredRepository(lifecycle, discoveredRepository))
                .isInstanceOfSatisfying(AttemptPreparationException.class, exception -> {
                    assertThat(exception.semanticFailure()).isEqualTo(failure);
                    assertThat(exception.lastCommittedLifecycle().state().budget().usedSteps())
                            .isEqualTo(lifecycle.state().budget().usedSteps() + 1);
                    assertThat(exception.lastCommittedLifecycle().state().revisionVector()
                            .revisionOf(discoveredRepository)).isEmpty();
                });
        assertThat(fixture.transitions().events()).hasSize(eventsBeforeProbe + 1);
    }

    @Test
    void concludesCommittedStateAndDomainRunWithCompatibleOutcomes() {
        RepositoryId repositoryId = repository("order-service");
        Fixture fixture = fixture(new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1")));
        AttemptLifecycle active = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId))));

        AttemptLifecycle concluded = fixture.manager().conclude(
                active, AttemptOutcome.COMPLETED, AnalysisOutcome.COMPLETED);

        assertThat(concluded.state().status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(concluded.run().currentAttempt().revisionVector())
                .isEqualTo(concluded.state().revisionVector());
        assertThat(concluded.run().currentAttempt().budget()).isEqualTo(concluded.state().budget());
        assertThat(concluded.run().currentAttempt().outcome()).contains(AttemptOutcome.COMPLETED);
        assertThat(concluded.run().outcome()).contains(AnalysisOutcome.COMPLETED);
        assertThat(fixture.transitions().events().getLast()).isInstanceOf(AnalysisEvent.AttemptConcluded.class);
    }

    @Test
    void rejectsIncompatibleConclusionBeforeCommittingAnEvent() {
        RepositoryId repositoryId = repository("order-service");
        Fixture fixture = fixture(new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1")));
        AttemptLifecycle active = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId))));
        int eventsBeforeConclusion = fixture.transitions().events().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().conclude(
                        active, AttemptOutcome.FAILED, AnalysisOutcome.COMPLETED))
                .withMessageContaining("compatible");

        assertThat(fixture.transitions().events()).hasSize(eventsBeforeConclusion);
    }

    @Test
    void rejectsConcludeAndRestartBeforeSideEffectsWhenRunIsAlreadyConcluded() {
        RepositoryId repositoryId = repository("order-service");
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1")), attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle active = fixture.manager().start(command);
        AttemptLifecycle terminalRunWithActiveState = new AttemptLifecycle(
                active.run().concludeCurrentAttempt(
                        active.state().revisionVector(),
                        active.state().budget(),
                        AttemptOutcome.STALE).conclude(AnalysisOutcome.INCONCLUSIVE),
                active.state(),
                active.revisionRestartCount());
        int eventsBeforeRejectedOperations = fixture.transitions().events().size();
        int generatedIdsBeforeRejectedOperations = attemptIds.calls().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().conclude(
                        terminalRunWithActiveState,
                        AttemptOutcome.STALE,
                        AnalysisOutcome.INCONCLUSIVE))
                .withMessageContaining("active lifecycle");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        terminalRunWithActiveState, command))
                .withMessageContaining("active lifecycle");

        assertThat(fixture.transitions().events()).hasSize(eventsBeforeRejectedOperations);
        assertThat(attemptIds.calls()).hasSize(generatedIdsBeforeRejectedOperations);
    }

    @Test
    void rejectsActiveOperationsBeforeSideEffectsWhenStateIsTerminal() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(revisions, attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle active = fixture.manager().start(command);
        AnalysisEvent.AttemptConcluded terminalEvent = new AnalysisEvent.AttemptConcluded(
                active.state().runId(),
                active.state().attemptId(),
                active.state().stateRevision(),
                AttemptOutcome.STALE);
        AttemptLifecycle activeRunWithTerminalState = new AttemptLifecycle(
                active.run(),
                fixture.committer().apply(active.state(), terminalEvent),
                active.revisionRestartCount());
        int eventsBeforeRejectedOperations = fixture.transitions().events().size();
        int repositoryCallsBeforeRejectedOperations = revisions.calls().size();
        int generatedIdsBeforeRejectedOperations = attemptIds.calls().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().conclude(
                        activeRunWithTerminalState,
                        AttemptOutcome.STALE,
                        AnalysisOutcome.INCONCLUSIVE))
                .withMessageContaining("active lifecycle");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        activeRunWithTerminalState, command))
                .withMessageContaining("active lifecycle");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().pinDiscoveredRepository(
                        activeRunWithTerminalState, repositoryId))
                .withMessageContaining("active lifecycle");

        assertThat(fixture.transitions().events()).hasSize(eventsBeforeRejectedOperations);
        assertThat(revisions.calls()).hasSize(repositoryCallsBeforeRejectedOperations);
        assertThat(attemptIds.calls()).hasSize(generatedIdsBeforeRejectedOperations);
    }

    @Test
    void propagatesCommitFailureWithoutAppendingConclusionOrMutatingActiveLifecycle() {
        RepositoryId repositoryId = repository("order-service");
        Fixture fixture = fixture(new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1")));
        AttemptLifecycle active = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId))));
        int eventsBeforeConclusion = fixture.transitions().events().size();
        long nextCommit = fixture.transitions().commitCount() + 1;
        fixture.transitions().failAtCommit(nextCommit);

        assertThatThrownBy(() -> fixture.manager().conclude(
                active, AttemptOutcome.STALE, AnalysisOutcome.INCONCLUSIVE))
                .isInstanceOf(AnalysisTransitionCommitException.class);

        assertThat(fixture.transitions().events()).hasSize(eventsBeforeConclusion);
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.AttemptConcluded.class::isInstance);
        assertThat(active.run().outcome()).isEmpty();
        assertThat(active.run().currentAttempt().outcome()).isEmpty();
        assertThat(active.state().status()).isEqualTo(AnalysisStatus.PLANNING);
    }

    @Test
    void concludesStaleAttemptWithInconclusiveRunOutcome() {
        RepositoryId repositoryId = repository("order-service");
        Fixture fixture = fixture(new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1")));
        AttemptLifecycle active = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId))));

        AttemptLifecycle concluded = fixture.manager().conclude(
                active, AttemptOutcome.STALE, AnalysisOutcome.INCONCLUSIVE);

        assertThat(concluded.state().status()).isEqualTo(AnalysisStatus.STALE);
        assertThat(concluded.run().currentAttempt().outcome()).contains(AttemptOutcome.STALE);
        assertThat(concluded.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
    }

    private AttemptLifecycle lifecycleWithDiscoveredScope(
            Fixture fixture,
            AttemptLifecycle lifecycle,
            RepositoryId discoveredRepository) {
        AnalysisEvent.ScopeExpanded event = new AnalysisEvent.ScopeExpanded(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                new RepositoryDiscovery(
                        discoveredRepository,
                        "discovered during lifecycle test",
                        evidence(lifecycle)),
                true);
        return new AttemptLifecycle(
                lifecycle.run(),
                fixture.committer().apply(lifecycle.state(), event),
                lifecycle.revisionRestartCount());
    }

    private AttemptLifecycle lifecycleWithEvidenceAndDiscoveredScope(
            Fixture fixture,
            AttemptLifecycle lifecycle,
            RepositoryId discoveredRepository) {
        AnalysisEvent.EvidenceAccepted evidenceEvent = new AnalysisEvent.EvidenceAccepted(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                lifecycle.state().pendingNeeds().firstKey(),
                evidence(lifecycle));
        AttemptLifecycle withEvidence = new AttemptLifecycle(
                lifecycle.run(),
                fixture.committer().apply(lifecycle.state(), evidenceEvent),
                lifecycle.revisionRestartCount());
        return lifecycleWithDiscoveredScope(fixture, withEvidence, discoveredRepository);
    }

    private EvidenceRef evidence(AttemptLifecycle lifecycle) {
        RepositoryId repositoryId = lifecycle.state().repositoryScope().repositoryIds().getFirst();
        RepositoryRevision revision = lifecycle.state().revisionVector().revisionOf(repositoryId).orElseThrow();
        return new EvidenceRef(
                "semantic-service",
                repositoryId,
                revision,
                new SemanticTarget(
                        SemanticTargetKind.REPOSITORY,
                        repositoryId.value(),
                        Optional.empty()),
                1.0,
                List.of(),
                new ArtifactRef("sha256:lifecycle-evidence"));
    }

    private Fixture fixture(RecordingRepositoryRevisionPort revisions) {
        return fixture(revisions, new RecordingAttemptIdGenerator());
    }

    private Fixture fixture(
            RecordingRepositoryRevisionPort revisions,
            RecordingAttemptIdGenerator attemptIds) {
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter();
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), transitions);
        return new Fixture(
                new AttemptLifecycleManager(committer, revisions, attemptIds), committer, transitions);
    }

    private AnalysisExecutionCommand command(
            String runId,
            String attemptId,
            List<RepositoryId> repositoryIds,
            List<InformationNeed> needs) {
        return new AnalysisExecutionCommand(
                new AnalysisRunId(runId),
                new AnalysisAttemptId(attemptId),
                RepositoryScope.of(repositoryIds.stream()
                        .map(repositoryId -> new RepositorySelection(
                                repositoryId,
                                "lifecycle test scope",
                                true,
                                RepositoryDiscoverySource.USER))
                        .toList()),
                needs,
                new Goal("complete lifecycle test", needs.stream()
                        .map(InformationNeed::id)
                        .collect(Collectors.toUnmodifiableSet())),
                AnalysisBudget.of(10, 5));
    }

    private InformationNeed need(String id, RepositoryId repositoryId) {
        return new InformationNeed(
                new InformationNeedId(id),
                InformationNeedType.METHOD_IMPLEMENTATION,
                "Resolve " + id,
                true,
                List.of(repositoryId),
                List.of());
    }

    private RepositoryId repository(String value) {
        return new RepositoryId(value);
    }

    private RepositoryRevisionResult ready(String revision) {
        return RepositoryRevisionResult.ready(new RepositoryRevision(revision));
    }

    @SafeVarargs
    private final void assertEventOrder(
            List<AnalysisEvent> events,
            Class<? extends AnalysisEvent>... expectedTypes) {
        List<Class<?>> actualTypes = events.stream().<Class<?>>map(AnalysisEvent::getClass).toList();
        List<Class<? extends AnalysisEvent>> expectedTypeList = List.of(expectedTypes);
        assertThat(actualTypes).containsExactlyElementsOf(expectedTypeList);
    }

    private record Fixture(
            AttemptLifecycleManager manager,
            TransitionCommitter committer,
            InMemoryAnalysisTransitionAdapter transitions) {
    }

    private record AttemptIdCall(AnalysisRunId runId, int attemptNumber) {
    }

    private static final class RecordingRepositoryRevisionPort implements RepositoryRevisionPort {

        private final Map<RepositoryId, Deque<RepositoryRevisionResult>> results = new TreeMap<>();
        private final List<RepositoryId> calls = new ArrayList<>();

        private RecordingRepositoryRevisionPort register(
                RepositoryId repositoryId,
                RepositoryRevisionResult... registeredResults) {
            Objects.requireNonNull(repositoryId, "repository ID must not be null");
            Objects.requireNonNull(registeredResults, "registered revisions must not be null");
            Deque<RepositoryRevisionResult> values = new ArrayDeque<>();
            for (RepositoryRevisionResult registeredResult : registeredResults) {
                values.addLast(Objects.requireNonNull(
                        registeredResult, "registered revision result must not be null"));
            }
            results.put(repositoryId, values);
            return this;
        }

        @Override
        public RepositoryRevisionResult currentRevision(RepositoryId repositoryId) {
            Objects.requireNonNull(repositoryId, "repository ID must not be null");
            calls.add(repositoryId);
            Deque<RepositoryRevisionResult> values = results.get(repositoryId);
            if (Objects.isNull(values) || values.size() == 0) {
                throw new IllegalStateException("missing repository revision result");
            }
            if (values.size() > 1) {
                return values.removeFirst();
            }
            return values.getFirst();
        }

        private List<RepositoryId> calls() {
            return List.copyOf(calls);
        }
    }

    private static final class RecordingAttemptIdGenerator implements AnalysisAttemptIdGenerator {

        private final Deque<AnalysisAttemptId> generatedIds = new ArrayDeque<>();
        private final List<AttemptIdCall> calls = new ArrayList<>();

        private RecordingAttemptIdGenerator(AnalysisAttemptId... attemptIds) {
            for (AnalysisAttemptId attemptId : attemptIds) {
                generatedIds.addLast(Objects.requireNonNull(
                        attemptId, "generated attempt ID must not be null"));
            }
        }

        @Override
        public AnalysisAttemptId nextAttemptId(AnalysisRunId runId, int attemptNumber) {
            Objects.requireNonNull(runId, "analysis run ID must not be null");
            calls.add(new AttemptIdCall(runId, attemptNumber));
            if (generatedIds.size() == 0) {
                throw new IllegalStateException("no generated attempt ID is registered");
            }
            return generatedIds.removeFirst();
        }

        private List<AttemptIdCall> calls() {
            return List.copyOf(calls);
        }
    }

}
