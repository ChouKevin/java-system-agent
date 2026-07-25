package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.AnalysisBudget;
import com.java.system.agent.runtime.domain.AnalysisOutcome;
import com.java.system.agent.runtime.domain.AnalysisRunId;
import com.java.system.agent.runtime.domain.AnalysisStatus;
import com.java.system.agent.runtime.domain.ArtifactRef;
import com.java.system.agent.runtime.domain.AttemptOutcome;
import com.java.system.agent.runtime.domain.EvidenceRef;
import com.java.system.agent.runtime.domain.Goal;
import com.java.system.agent.runtime.domain.InformationNeed;
import com.java.system.agent.runtime.domain.InformationNeedId;
import com.java.system.agent.runtime.domain.InformationNeedType;
import com.java.system.agent.runtime.domain.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.RepositoryId;
import com.java.system.agent.runtime.domain.RepositoryRevision;
import com.java.system.agent.runtime.domain.RepositoryScope;
import com.java.system.agent.runtime.domain.RepositorySelection;
import com.java.system.agent.runtime.domain.RevisionVector;
import com.java.system.agent.runtime.domain.SemanticTarget;
import com.java.system.agent.runtime.domain.SemanticTargetKind;
import com.java.system.agent.runtime.port.in.AnalysisExecutionCommand;
import com.java.system.agent.runtime.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.runtime.port.out.AnalysisTransitionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import com.java.system.agent.runtime.port.out.SemanticFailure;
import com.java.system.agent.runtime.port.out.SemanticFailureCode;
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
    void retainsProbedLifecycleWhenStartingRevisionProbeThrows() {
        RepositoryId repositoryId = repository("order-service");
        IllegalStateException expected = new IllegalStateException("revision source failed");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .registerFailure(repositoryId, expected);
        Fixture fixture = fixture(revisions);

        assertThatThrownBy(() -> fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)))))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(expected);
                    assertProbedLifecycle(
                            exception.lastCommittedLifecycle(), repositoryId, 1, AnalysisStatus.REVISION_PINNING);
                });
        assertThat(revisions.calls()).containsExactly(repositoryId);
        assertEventOrder(fixture.transitions().events(),
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class);
    }

    @Test
    void propagatesTheExactRepositoryRevisionErrorWithoutWrappingDuringStart() {
        RepositoryId repositoryId = repository("order-service");
        Error expected = new AssertionError("sentinel revision error");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .registerResponse(repositoryId, () -> {
                    throw expected;
                });
        Fixture fixture = fixture(revisions);

        assertThatThrownBy(() -> fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)))))
                .isSameAs(expected)
                .isNotInstanceOf(AttemptLifecycleExternalFailureException.class);
        assertThat(revisions.calls()).containsExactly(repositoryId);
        assertThat(fixture.transitions().commitCount()).isEqualTo(2);
        assertEventOrder(fixture.transitions().events(),
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class);
    }

    @Test
    void retainsProbedLifecycleWhenStartingRevisionProbeReturnsNull() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .registerNull(repositoryId);
        Fixture fixture = fixture(revisions);

        assertThatThrownBy(() -> fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)))))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception).hasCauseInstanceOf(NullPointerException.class);
                    assertProbedLifecycle(
                            exception.lastCommittedLifecycle(), repositoryId, 1, AnalysisStatus.REVISION_PINNING);
                });
        assertThat(revisions.calls()).containsExactly(repositoryId);
        assertEventOrder(fixture.transitions().events(),
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class);
    }

    @Test
    void restartsForAnEquivalentCommandWithAStaleOldAttemptFreshStateAndFullPriorScope() {
        RepositoryId initialRepository = repository("order-service");
        RepositoryId discoveredRepository = repository("notification-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(initialRepository, ready("order-1"), ready("order-2"))
                .register(discoveredRepository, ready("notification-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator(new AnalysisAttemptId("attempt-2"));
        Fixture fixture = fixture(revisions, attemptIds);
        InformationNeed firstInformationNeed = need("need-1", initialRepository);
        InformationNeed secondInformationNeed = need("need-2", initialRepository);
        AttemptLifecycle initial = fixture.manager().start(command(
                "run-1",
                "attempt-1",
                List.of(initialRepository),
                List.of(firstInformationNeed, secondInformationNeed)));
        AttemptLifecycle withEvidenceAndDiscovery = lifecycleWithEvidenceAndDiscoveredScope(
                fixture, initial, discoveredRepository);

        AttemptLifecycle restarted = fixture.manager().restartAfterRevisionMismatch(
                withEvidenceAndDiscovery,
                command(
                        "run-1",
                        "attempt-1",
                        List.of(initialRepository),
                        List.of(secondInformationNeed, firstInformationNeed)));

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
        assertThat(restarted.state().pendingNeeds())
                .containsOnlyKeys(firstInformationNeed.id(), secondInformationNeed.id());
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
    void retainsRestartPreparationLifecycleWhenRevisionProbeThrows() {
        RepositoryId repositoryId = repository("order-service");
        IllegalStateException expected = new IllegalStateException("revision source failed");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"))
                .registerFailure(repositoryId, expected);
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator(new AnalysisAttemptId("attempt-2"));
        Fixture fixture = fixture(revisions, attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle initial = fixture.manager().start(command);

        assertThatThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(initial, command))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(expected);
                    assertRestartProbeLifecycle(exception.lastCommittedLifecycle(), repositoryId);
                });
        assertThat(revisions.calls()).containsExactly(repositoryId, repositoryId);
        assertThat(attemptIds.calls()).containsExactly(new AttemptIdCall(new AnalysisRunId("run-1"), 2));
        assertEventOrder(fixture.transitions().events(),
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class,
                AnalysisEvent.RevisionPinned.class,
                AnalysisEvent.NeedRegistered.class,
                AnalysisEvent.AttemptConcluded.class,
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class);
    }

    @Test
    void retainsRestartPreparationLifecycleWhenRevisionProbeReturnsNull() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"))
                .registerNull(repositoryId);
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator(new AnalysisAttemptId("attempt-2"));
        Fixture fixture = fixture(revisions, attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle initial = fixture.manager().start(command);

        assertThatThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(initial, command))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception).hasCauseInstanceOf(NullPointerException.class);
                    assertRestartProbeLifecycle(exception.lastCommittedLifecycle(), repositoryId);
                });
        assertThat(revisions.calls()).containsExactly(repositoryId, repositoryId);
        assertThat(attemptIds.calls()).containsExactly(new AttemptIdCall(new AnalysisRunId("run-1"), 2));
        assertEventOrder(fixture.transitions().events(),
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class,
                AnalysisEvent.RevisionPinned.class,
                AnalysisEvent.NeedRegistered.class,
                AnalysisEvent.AttemptConcluded.class,
                AnalysisEvent.ScopeResolved.class,
                AnalysisEvent.BudgetConsumed.class);
    }

    @Test
    void retainsStaleLifecycleWhenRestartAttemptIdGenerationThrows() {
        RepositoryId repositoryId = repository("order-service");
        IllegalStateException expected = new IllegalStateException("attempt ID generator failed");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator().failWith(expected);
        Fixture fixture = fixture(revisions, attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle initial = fixture.manager().start(command);

        assertThatThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(initial, command))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(expected);
                    assertStaleLifecycle(exception.lastCommittedLifecycle(), repositoryId);
                });
        assertThat(revisions.calls()).containsExactly(repositoryId);
        assertThat(attemptIds.calls()).containsExactly(new AttemptIdCall(new AnalysisRunId("run-1"), 2));
        assertThat(fixture.transitions().events().getLast()).isInstanceOf(AnalysisEvent.AttemptConcluded.class);
    }

    @Test
    void retainsStaleLifecycleWhenRestartAttemptIdGenerationReturnsNull() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator().returnNull();
        Fixture fixture = fixture(revisions, attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle initial = fixture.manager().start(command);

        assertThatThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(initial, command))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception).hasCauseInstanceOf(NullPointerException.class);
                    assertStaleLifecycle(exception.lastCommittedLifecycle(), repositoryId);
                });
        assertThat(revisions.calls()).containsExactly(repositoryId);
        assertThat(attemptIds.calls()).containsExactly(new AttemptIdCall(new AnalysisRunId("run-1"), 2));
        assertThat(fixture.transitions().events().getLast()).isInstanceOf(AnalysisEvent.AttemptConcluded.class);
    }

    @Test
    void retainsTheStaleLifecycleWhenRestartAttemptIdDuplicatesAnExistingAttempt() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator(new AnalysisAttemptId("attempt-1"));
        Fixture fixture = fixture(revisions, attemptIds);
        AnalysisExecutionCommand command = command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)));
        AttemptLifecycle initial = fixture.manager().start(command);

        assertThatThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(initial, command))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception).hasCauseInstanceOf(IllegalArgumentException.class);
                    assertStaleLifecycle(exception.lastCommittedLifecycle(), repositoryId);
                });
        assertThat(revisions.calls()).containsExactly(repositoryId);
        assertThat(fixture.transitions().events().getLast()).isInstanceOf(AnalysisEvent.AttemptConcluded.class);
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
        int repositoryCallsBeforeRejectedRestart = revisions.calls().size();
        int callsBeforeRejectedRestart = attemptIds.calls().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(restarted, command))
                .withMessageContaining("already been restarted");

        assertThat(fixture.transitions().events()).hasSize((int) eventsBeforeRejectedRestart);
        assertThat(revisions.calls()).hasSize(repositoryCallsBeforeRejectedRestart);
        assertThat(attemptIds.calls()).hasSize(callsBeforeRejectedRestart);
    }

    @Test
    void rejectsRestartForACommandWithAnotherFirstAttemptBeforeAnySideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        Fixture fixture = fixture(revisions, attemptIds);
        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId))));
        int eventsBeforeRejectedRestart = fixture.transitions().events().size();
        int repositoryCallsBeforeRejectedRestart = revisions.calls().size();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        lifecycle,
                        command("run-1", "other-attempt", List.of(repositoryId),
                                List.of(need("need-1", repositoryId)))))
                .withMessageContaining("first attempt");

        assertThat(fixture.transitions().events()).hasSize(eventsBeforeRejectedRestart);
        assertThat(revisions.calls()).hasSize(repositoryCallsBeforeRejectedRestart);
        assertThat(attemptIds.calls()).isEmpty();
    }

    @Test
    void shouldRejectRestartWhenCommandOmitsAnOriginalInformationNeedBeforeAnySideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(revisions, attemptIds);
        InformationNeed firstNeed = need("need-1", repositoryId);
        InformationNeed secondNeed = need("need-2", repositoryId);
        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(firstNeed, secondNeed)));
        RestartSideEffects sideEffectsBeforeRejection = restartSideEffects(fixture, revisions, attemptIds);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        lifecycle,
                        command("run-1", "attempt-1", List.of(repositoryId), List.of(firstNeed))))
                .withMessageContaining("information need IDs");

        assertRestartSideEffectsUnchanged(fixture, revisions, attemptIds, sideEffectsBeforeRejection);
    }

    @Test
    void shouldRejectRestartWhenCommandAddsAnInformationNeedBeforeAnySideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(revisions, attemptIds);
        InformationNeed originalNeed = need("need-1", repositoryId);
        InformationNeed addedNeed = need("need-2", repositoryId);
        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(originalNeed)));
        RestartSideEffects sideEffectsBeforeRejection = restartSideEffects(fixture, revisions, attemptIds);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        lifecycle,
                        command(
                                "run-1",
                                "attempt-1",
                                List.of(repositoryId),
                                List.of(originalNeed, addedNeed))))
                .withMessageContaining("information need IDs");

        assertRestartSideEffectsUnchanged(fixture, revisions, attemptIds, sideEffectsBeforeRejection);
    }

    @Test
    void shouldRejectRestartWhenAPendingInformationNeedDetailsChangeBeforeAnySideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(revisions, attemptIds);
        InformationNeed originalNeed = need("need-1", repositoryId);
        InformationNeed changedNeed = new InformationNeed(
                originalNeed.id(),
                originalNeed.type(),
                "Resolve a different question",
                originalNeed.required(),
                originalNeed.repositoryCandidates(),
                originalNeed.targetHints());
        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(originalNeed)));
        RestartSideEffects sideEffectsBeforeRejection = restartSideEffects(fixture, revisions, attemptIds);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        lifecycle,
                        command("run-1", "attempt-1", List.of(repositoryId), List.of(changedNeed))))
                .withMessageContaining("pending information need");

        assertRestartSideEffectsUnchanged(fixture, revisions, attemptIds, sideEffectsBeforeRejection);
    }

    @Test
    void shouldRejectRestartWhenCommandAttemptBudgetLimitsChangeBeforeAnySideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(revisions, attemptIds);
        InformationNeed informationNeed = need("need-1", repositoryId);
        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(informationNeed)));
        RestartSideEffects sideEffectsBeforeRejection = restartSideEffects(fixture, revisions, attemptIds);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        lifecycle,
                        command(
                                "run-1",
                                "attempt-1",
                                List.of(repositoryId),
                                List.of(informationNeed),
                                AnalysisBudget.of(11, 5))))
                .withMessageContaining("budget limits");

        assertRestartSideEffectsUnchanged(fixture, revisions, attemptIds, sideEffectsBeforeRejection);
    }

    @Test
    void shouldRejectRestartWhenCommandOmitsAResolvedInformationNeedBeforeAnySideEffect() {
        RepositoryId repositoryId = repository("order-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        RecordingAttemptIdGenerator attemptIds = new RecordingAttemptIdGenerator();
        Fixture fixture = fixture(revisions, attemptIds);
        InformationNeed resolvedNeed = need("need-1", repositoryId);
        InformationNeed pendingNeed = need("need-2", repositoryId);
        AttemptLifecycle lifecycle = fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(resolvedNeed, pendingNeed)));
        AttemptLifecycle lifecycleWithResolvedNeed = lifecycleWithResolvedNeed(
                fixture, lifecycle, resolvedNeed.id());
        RestartSideEffects sideEffectsBeforeRejection = restartSideEffects(fixture, revisions, attemptIds);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixture.manager().restartAfterRevisionMismatch(
                        lifecycleWithResolvedNeed,
                        command("run-1", "attempt-1", List.of(repositoryId), List.of(pendingNeed))))
                .withMessageContaining("information need IDs");

        assertRestartSideEffectsUnchanged(fixture, revisions, attemptIds, sideEffectsBeforeRejection);
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
    void retainsProbedLifecycleWhenDiscoveredRepositoryRevisionProbeThrows() {
        RepositoryId initialRepository = repository("order-service");
        RepositoryId discoveredRepository = repository("notification-service");
        IllegalStateException expected = new IllegalStateException("revision source failed");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(initialRepository, ready("order-1"))
                .registerFailure(discoveredRepository, expected);
        Fixture fixture = fixture(revisions);
        AttemptLifecycle lifecycle = lifecycleWithEvidenceAndDiscoveredScope(
                fixture,
                fixture.manager().start(command(
                        "run-1", "attempt-1", List.of(initialRepository),
                        List.of(need("need-1", initialRepository)))),
                discoveredRepository);
        int eventsBeforeProbe = fixture.transitions().events().size();

        assertThatThrownBy(() -> fixture.manager().pinDiscoveredRepository(lifecycle, discoveredRepository))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(expected);
                    assertProbedLifecycle(
                            exception.lastCommittedLifecycle(),
                            discoveredRepository,
                            lifecycle.state().budget().usedSteps() + 1,
                            AnalysisStatus.EXECUTING);
                });
        assertThat(revisions.calls()).containsExactly(initialRepository, discoveredRepository);
        assertThat(fixture.transitions().events()).hasSize(eventsBeforeProbe + 1);
    }

    @Test
    void retainsProbedLifecycleWhenDiscoveredRepositoryRevisionProbeReturnsNull() {
        RepositoryId initialRepository = repository("order-service");
        RepositoryId discoveredRepository = repository("notification-service");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(initialRepository, ready("order-1"))
                .registerNull(discoveredRepository);
        Fixture fixture = fixture(revisions);
        AttemptLifecycle lifecycle = lifecycleWithEvidenceAndDiscoveredScope(
                fixture,
                fixture.manager().start(command(
                        "run-1", "attempt-1", List.of(initialRepository),
                        List.of(need("need-1", initialRepository)))),
                discoveredRepository);
        int eventsBeforeProbe = fixture.transitions().events().size();

        assertThatThrownBy(() -> fixture.manager().pinDiscoveredRepository(lifecycle, discoveredRepository))
                .isInstanceOfSatisfying(AttemptLifecycleExternalFailureException.class, exception -> {
                    assertThat(exception).hasCauseInstanceOf(NullPointerException.class);
                    assertProbedLifecycle(
                            exception.lastCommittedLifecycle(),
                            discoveredRepository,
                            lifecycle.state().budget().usedSteps() + 1,
                            AnalysisStatus.EXECUTING);
                });
        assertThat(revisions.calls()).containsExactly(initialRepository, discoveredRepository);
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
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        Fixture fixture = fixture(revisions, attemptIds);
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
        int repositoryCallsBeforeRejectedOperations = revisions.calls().size();
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
        assertThat(revisions.calls()).hasSize(repositoryCallsBeforeRejectedOperations);
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
    void propagatesTheOriginalBudgetCommitFailureBeforeInvokingRevisionPort() {
        RepositoryId repositoryId = repository("order-service");
        AnalysisTransitionCommitException expected = new AnalysisTransitionCommitException(
                "sentinel budget commit failure");
        RecordingRepositoryRevisionPort revisions = new RecordingRepositoryRevisionPort()
                .register(repositoryId, ready("order-1"));
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter();
        List<StateTransition> transitionCalls = new ArrayList<>();
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> {
            transitionCalls.add(transition);
            if (transition.event() instanceof AnalysisEvent.BudgetConsumed) {
                throw expected;
            }
            return transitions.commit(transition);
        };
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), transitionPort);
        Fixture fixture = new Fixture(
                new AttemptLifecycleManager(committer, revisions, new RecordingAttemptIdGenerator()),
                committer,
                transitions);

        assertThatThrownBy(() -> fixture.manager().start(command(
                "run-1", "attempt-1", List.of(repositoryId), List.of(need("need-1", repositoryId)))))
                .isSameAs(expected)
                .isNotInstanceOf(AttemptLifecycleExternalFailureException.class);
        assertThat(transitionCalls).hasSize(2);
        assertThat(fixture.transitions().commitCount()).isEqualTo(1);
        assertThat(revisions.calls()).isEmpty();
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

    private AttemptLifecycle lifecycleWithResolvedNeed(
            Fixture fixture,
            AttemptLifecycle lifecycle,
            InformationNeedId informationNeedId) {
        AnalysisEvent.EvidenceAccepted evidenceEvent = new AnalysisEvent.EvidenceAccepted(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                informationNeedId,
                evidence(lifecycle));
        AttemptLifecycle lifecycleWithEvidence = new AttemptLifecycle(
                lifecycle.run(),
                fixture.committer().apply(lifecycle.state(), evidenceEvent),
                lifecycle.revisionRestartCount());
        AnalysisEvent.NeedResolved needResolvedEvent = new AnalysisEvent.NeedResolved(
                lifecycleWithEvidence.state().runId(),
                lifecycleWithEvidence.state().attemptId(),
                lifecycleWithEvidence.state().stateRevision(),
                informationNeedId);
        return new AttemptLifecycle(
                lifecycleWithEvidence.run(),
                fixture.committer().apply(lifecycleWithEvidence.state(), needResolvedEvent),
                lifecycleWithEvidence.revisionRestartCount());
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
        return command(runId, attemptId, repositoryIds, needs, AnalysisBudget.of(10, 5));
    }

    private AnalysisExecutionCommand command(
            String runId,
            String attemptId,
            List<RepositoryId> repositoryIds,
            List<InformationNeed> needs,
            AnalysisBudget attemptBudget) {
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
                attemptBudget);
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

    private void assertProbedLifecycle(
            AttemptLifecycle lifecycle,
            RepositoryId unpinnedRepository,
            int expectedUsedSteps,
            AnalysisStatus expectedStatus) {
        assertThat(lifecycle.state().status()).isEqualTo(expectedStatus);
        assertThat(lifecycle.state().budget().usedSteps()).isEqualTo(expectedUsedSteps);
        assertThat(lifecycle.state().revisionVector().revisionOf(unpinnedRepository)).isEmpty();
        assertThat(lifecycle.run().outcome()).isEmpty();
        assertThat(lifecycle.run().currentAttempt().outcome()).isEmpty();
    }

    private void assertRestartProbeLifecycle(AttemptLifecycle lifecycle, RepositoryId repositoryId) {
        assertProbedLifecycle(lifecycle, repositoryId, 1, AnalysisStatus.REVISION_PINNING);
        assertThat(lifecycle.revisionRestartCount()).isEqualTo(1);
        assertThat(lifecycle.run().attempts()).hasSize(2);
        assertThat(lifecycle.run().attempts().getFirst().outcome()).contains(AttemptOutcome.STALE);
    }

    private void assertStaleLifecycle(AttemptLifecycle lifecycle, RepositoryId repositoryId) {
        assertThat(lifecycle.state().status()).isEqualTo(AnalysisStatus.STALE);
        assertThat(lifecycle.state().revisionVector().revisionOf(repositoryId))
                .contains(new RepositoryRevision("order-1"));
        assertThat(lifecycle.state().budget().usedSteps()).isEqualTo(1);
        assertThat(lifecycle.run().currentAttempt().outcome()).contains(AttemptOutcome.STALE);
        assertThat(lifecycle.run().outcome()).isEmpty();
        assertThat(lifecycle.revisionRestartCount()).isZero();
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

    private record RestartSideEffects(
            int transitionEventCount,
            int repositoryRevisionCallCount,
            int attemptIdGeneratorCallCount) {
    }

    private record AttemptIdCall(AnalysisRunId runId, int attemptNumber) {
    }

    private RestartSideEffects restartSideEffects(
            Fixture fixture,
            RecordingRepositoryRevisionPort revisions,
            RecordingAttemptIdGenerator attemptIds) {
        return new RestartSideEffects(
                fixture.transitions().events().size(),
                revisions.calls().size(),
                attemptIds.calls().size());
    }

    private void assertRestartSideEffectsUnchanged(
            Fixture fixture,
            RecordingRepositoryRevisionPort revisions,
            RecordingAttemptIdGenerator attemptIds,
            RestartSideEffects sideEffectsBeforeRejection) {
        assertThat(fixture.transitions().events()).hasSize(sideEffectsBeforeRejection.transitionEventCount());
        assertThat(revisions.calls()).hasSize(sideEffectsBeforeRejection.repositoryRevisionCallCount());
        assertThat(attemptIds.calls()).hasSize(sideEffectsBeforeRejection.attemptIdGeneratorCallCount());
    }

    private static final class RecordingRepositoryRevisionPort implements RepositoryRevisionPort {

        private final Map<RepositoryId, Deque<RepositoryRevisionResponse>> results = new TreeMap<>();
        private final List<RepositoryId> calls = new ArrayList<>();

        private RecordingRepositoryRevisionPort register(
                RepositoryId repositoryId,
                RepositoryRevisionResult... registeredResults) {
            Objects.requireNonNull(repositoryId, "repository ID must not be null");
            Objects.requireNonNull(registeredResults, "registered revisions must not be null");
            Deque<RepositoryRevisionResponse> values = results.computeIfAbsent(
                    repositoryId, ignored -> new ArrayDeque<>());
            for (RepositoryRevisionResult registeredResult : registeredResults) {
                RepositoryRevisionResult nonNullResult = Objects.requireNonNull(
                        registeredResult, "registered revision result must not be null");
                values.addLast(() -> nonNullResult);
            }
            return this;
        }

        private RecordingRepositoryRevisionPort registerFailure(
                RepositoryId repositoryId,
                RuntimeException failure) {
            Objects.requireNonNull(failure, "repository revision failure must not be null");
            return registerResponse(repositoryId, () -> {
                throw failure;
            });
        }

        private RecordingRepositoryRevisionPort registerNull(RepositoryId repositoryId) {
            return registerResponse(repositoryId, () -> null);
        }

        private RecordingRepositoryRevisionPort registerResponse(
                RepositoryId repositoryId,
                RepositoryRevisionResponse response) {
            Objects.requireNonNull(repositoryId, "repository ID must not be null");
            Objects.requireNonNull(response, "repository revision response must not be null");
            results.computeIfAbsent(repositoryId, ignored -> new ArrayDeque<>()).addLast(response);
            return this;
        }

        @Override
        public RepositoryRevisionResult currentRevision(RepositoryId repositoryId) {
            Objects.requireNonNull(repositoryId, "repository ID must not be null");
            calls.add(repositoryId);
            Deque<RepositoryRevisionResponse> values = results.get(repositoryId);
            if (Objects.isNull(values) || values.size() == 0) {
                throw new IllegalStateException("missing repository revision result");
            }
            if (values.size() > 1) {
                return values.removeFirst().respond();
            }
            return values.getFirst().respond();
        }

        private List<RepositoryId> calls() {
            return List.copyOf(calls);
        }

        private interface RepositoryRevisionResponse {

            RepositoryRevisionResult respond();
        }
    }

    private static final class RecordingAttemptIdGenerator implements AnalysisAttemptIdGenerator {

        private final Deque<AnalysisAttemptId> generatedIds = new ArrayDeque<>();
        private final List<AttemptIdCall> calls = new ArrayList<>();
        private RuntimeException failure;
        private boolean returnsNull;

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
            if (Objects.nonNull(failure)) {
                throw failure;
            }
            if (returnsNull) {
                return null;
            }
            if (generatedIds.size() == 0) {
                throw new IllegalStateException("no generated attempt ID is registered");
            }
            return generatedIds.removeFirst();
        }

        private RecordingAttemptIdGenerator failWith(RuntimeException generatedFailure) {
            failure = Objects.requireNonNull(generatedFailure, "generated failure must not be null");
            return this;
        }

        private RecordingAttemptIdGenerator returnNull() {
            returnsNull = true;
            return this;
        }

        private List<AttemptIdCall> calls() {
            return List.copyOf(calls);
        }
    }

}
