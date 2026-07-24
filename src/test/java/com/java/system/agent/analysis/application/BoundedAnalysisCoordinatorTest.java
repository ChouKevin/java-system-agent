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
import com.java.system.agent.analysis.domain.SemanticTarget;
import com.java.system.agent.analysis.domain.SemanticTargetKind;
import com.java.system.agent.analysis.port.in.AnalysisExecutionCommand;
import com.java.system.agent.analysis.port.in.AnalysisExecutionException;
import com.java.system.agent.analysis.port.in.AnalysisExecutionResult;
import com.java.system.agent.analysis.port.in.AnalysisTerminationReason;
import com.java.system.agent.analysis.port.out.AnalysisCancellationPort;
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;
import com.java.system.agent.analysis.port.out.RepositoryRevisionPort;
import com.java.system.agent.analysis.port.out.RepositoryRevisionResult;
import com.java.system.agent.analysis.port.out.SemanticFailure;
import com.java.system.agent.analysis.port.out.SemanticFailureCode;
import com.java.system.agent.analysis.port.out.SemanticQuery;
import com.java.system.agent.analysis.port.out.SemanticQueryPort;
import com.java.system.agent.analysis.port.out.SemanticQueryResult;
import com.java.system.agent.analysis.port.out.SemanticResultStatus;
import com.java.system.agent.runtime.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.runtime.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.runtime.adapter.fake.InMemoryAnalysisTransitionAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedAnalysisCoordinatorTest {

    @Test
    void completesASingleRepositoryNeedWithRevisionBoundEvidence() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, success(orders, ordersRevision, "orders-evidence"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-single", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.COMPLETED);
        assertThat(result.finalState().status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.GOAL_COMPLETED);
        assertThat(result.finalState().resolvedNeedIds()).containsExactly(need.id());
        assertThat(result.finalState().evidenceBindings()).hasSize(1);
        assertThat(fixture.transitions().events()).isNotEmpty();
        assertThatAcceptedEvidenceMatchesFinalVector(result);
    }

    @Test
    void pinsEveryInitialRepositoryBeforeIssuingTheFirstSemanticQuery() {
        RepositoryId alpha = repository("alpha");
        RepositoryId zeta = repository("zeta");
        RepositoryRevision alphaRevision = revision("alpha-a");
        RepositoryRevision zetaRevision = revision("zeta-a");
        InformationNeed alphaNeed = need("need-alpha", alpha);
        InformationNeed zetaNeed = need("need-zeta", zeta);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(alpha, alphaRevision)
                .register(zeta, zetaRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(alpha, alphaRevision, success(alpha, alphaRevision, "alpha-evidence"))
                .register(zeta, zetaRevision, success(zeta, zetaRevision, "zeta-evidence"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-multi", List.of(zeta, alpha), List.of(zetaNeed, alphaNeed)));

        assertThat(revisions.callsBeforeFirstSemanticQuery()).containsExactly(alpha, zeta);
        assertThat(result.finalState().evidenceBindings())
                .extracting(binding -> binding.evidenceRef().repositoryId())
                .containsExactly(alpha, zeta);
        assertThatAcceptedEvidenceMatchesFinalVector(result);
    }

    @Test
    void pinsAnEvidenceDiscoveredOptionalRepositoryBeforeExecutingItsNeed() {
        RepositoryId orders = repository("orders");
        RepositoryId notifications = repository("notifications");
        RepositoryRevision ordersRevision = revision("orders-a");
        RepositoryRevision notificationsRevision = revision("notifications-a");
        InformationNeed ordersNeed = need("need-a-orders", orders);
        InformationNeed notificationsNeed = need("need-b-notifications", notifications);
        EvidenceRef ordersEvidence = evidence(orders, ordersRevision, "orders-evidence");
        RepositoryDiscovery discovery = new RepositoryDiscovery(
                notifications, "orders publish notification", ordersEvidence);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision)
                .register(notifications, notificationsRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(ordersRevision),
                        List.of(ordersEvidence),
                        List.of(discovery),
                        Optional.empty()))
                .register(notifications, notificationsRevision,
                        success(notifications, notificationsRevision, "notifications-evidence"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-discovery", List.of(orders), List.of(ordersNeed, notificationsNeed)));

        assertThat(revisions.calls()).containsExactly(orders, notifications);
        assertThat(semanticPort.queries()).extracting(SemanticQuery::repositoryId)
                .containsExactly(orders, notifications);
        assertThat(result.finalState().repositoryScope().repositoryIds())
                .containsExactly(notifications, orders);
        assertThat(result.finalState().evidenceBindings())
                .extracting(binding -> binding.evidenceRef().repositoryId())
                .containsExactly(orders, notifications);
        assertThatAcceptedEvidenceMatchesFinalVector(result);
    }

    @Test
    void concludesFailedWhenSemanticExecutionReportsAnEngineFailure() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, failure(
                        SemanticFailureCode.ENGINE_FAILURE, "semantic engine failed"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-engine-failure", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.FAILED);
        assertThat(result.finalState().status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.SEMANTIC_UNAVAILABLE);
    }

    @Test
    void concludesWithTypedReasonWhenRevisionPreparationIsUnavailable() {
        RepositoryId orders = repository("orders");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .registerFailure(orders, new SemanticFailure(
                        SemanticFailureCode.ENGINE_UNAVAILABLE,
                        "revision service unavailable",
                        true));
        Fixture fixture = fixture(revisions, new RecordingSemanticPort());

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-preparation-failure", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.finalState().status()).isEqualTo(AnalysisStatus.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.SEMANTIC_UNAVAILABLE);
    }

    @Test
    void latchesCancellationObservedBeforePinningADiscoveredRepository() {
        RepositoryId orders = repository("orders");
        RepositoryId notifications = repository("notifications");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed ordersNeed = need("need-a-orders", orders);
        InformationNeed notificationsNeed = need("need-b-notifications", notifications);
        EvidenceRef ordersEvidence = evidence(orders, ordersRevision, "orders-evidence");
        RepositoryDiscovery discovery = new RepositoryDiscovery(
                notifications, "orders publish notification", ordersEvidence);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision)
                .register(notifications, revision("notifications-a"));
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(ordersRevision),
                        List.of(ordersEvidence),
                        List.of(discovery),
                        Optional.empty()));
        SequenceCancellationPort cancellationPort = new SequenceCancellationPort(
                false, false, true, false);
        Fixture fixture = fixture(revisions, semanticPort, cancellationPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-discovery-cancel", List.of(orders), List.of(ordersNeed, notificationsNeed)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.CANCELLED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.CANCELLED);
        assertThat(revisions.calls()).containsExactly(orders);
        assertThat(cancellationPort.checkCount()).isEqualTo(3);
    }

    @Test
    void restartsWithTheNewRevisionAndKeepsOnlyReplacementAttemptEvidence() {
        RepositoryId orders = repository("orders");
        RepositoryRevision revisionA = revision("orders-a");
        RepositoryRevision revisionB = revision("orders-b");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .registerSequence(orders, revisionA, revisionB);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, revisionA, revisionMismatch(revisionB))
                .register(orders, revisionB, success(orders, revisionB, "orders-b-evidence"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-revision-restart", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.COMPLETED);
        assertThat(result.run().attempts())
                .extracting(attempt -> attempt.outcome().orElseThrow())
                .containsExactly(AttemptOutcome.STALE, AttemptOutcome.COMPLETED);
        assertThat(revisions.calls()).containsExactly(orders, orders);
        assertThat(semanticPort.queries())
                .extracting(SemanticQuery::expectedRevision)
                .containsExactly(revisionA, revisionB);
        assertThat(result.finalState().evidenceBindings())
                .extracting(binding -> binding.evidenceRef().repositoryRevision())
                .containsExactly(revisionB);
    }

    @Test
    void secondRevisionMismatchConcludesAtTheRestartLimitWithoutAThirdAttempt() {
        RepositoryId orders = repository("orders");
        RepositoryRevision revisionA = revision("orders-a");
        RepositoryRevision revisionB = revision("orders-b");
        RepositoryRevision revisionC = revision("orders-c");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .registerSequence(orders, revisionA, revisionB);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, revisionA, revisionMismatch(revisionB))
                .register(orders, revisionB, revisionMismatch(revisionC));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-revision-limit", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.REVISION_RESTART_LIMIT);
        assertThat(result.run().attempts())
                .extracting(attempt -> attempt.outcome().orElseThrow())
                .containsExactly(AttemptOutcome.STALE, AttemptOutcome.STALE);
        assertThat(result.run().attempts()).hasSize(2);
        assertThat(result.finalState().evidenceBindings()).isEmpty();
        assertThat(revisions.calls()).containsExactly(orders, orders);
        assertThat(semanticPort.queries())
                .extracting(SemanticQuery::expectedRevision)
                .containsExactly(revisionA, revisionB);
    }

    @Test
    void retryableTimeoutThenSuccessUsesExactlyTwoSemanticCalls() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .registerSequence(
                        orders,
                        ordersRevision,
                        timeout(true),
                        success(orders, ordersRevision, "orders-evidence"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-retry-success", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.COMPLETED);
        assertThat(semanticPort.queries()).hasSize(2);
    }

    @Test
    void twoRetryableTimeoutsConcludeAsSemanticUnavailable() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .registerSequence(orders, ordersRevision, timeout(true), timeout(true));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-retry-timeout", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.SEMANTIC_UNAVAILABLE);
        assertThat(semanticPort.queries()).hasSize(2);
    }

    @Test
    void nonRetryableTimeoutDoesNotRetry() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, timeout(false));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-non-retry-timeout", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.SEMANTIC_UNAVAILABLE);
        assertThat(semanticPort.queries()).hasSize(1);
    }

    @Test
    void repeatedPartialResultAcceptsEvidenceOnceThenConcludesForNoProgress() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        SemanticQueryResult partial = partial(orders, ordersRevision, "orders-partial");
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, partial);
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-no-progress", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.NO_PROGRESS);
        assertThat(result.finalState().evidenceBindings()).hasSize(1);
        assertThat(fixture.transitions().events())
                .filteredOn(AnalysisEvent.EvidenceAccepted.class::isInstance)
                .hasSize(1);
        assertThat(semanticPort.queries()).hasSize(2);
    }

    @Test
    void retryableTimeoutThenPartialResultDoesNotIssueAThirdSemanticCallForTheNeed() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .registerSequence(
                        orders,
                        ordersRevision,
                        timeout(true),
                        partial(orders, ordersRevision, "orders-partial"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-timeout-partial-call-limit", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.NO_PROGRESS);
        assertThat(semanticPort.queries()).hasSize(2);
        assertThat(result.finalState().budget().usedSemanticCalls()).isEqualTo(2);
    }

    @Test
    void changingPartialResultsDoNotIssueAThirdSemanticCallForTheNeed() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .registerSequence(
                        orders,
                        ordersRevision,
                        partial(orders, ordersRevision, "orders-partial-one"),
                        partial(orders, ordersRevision, "orders-partial-two"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-changing-partials-call-limit", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.NO_PROGRESS);
        assertThat(semanticPort.queries()).hasSize(2);
        assertThat(result.finalState().evidenceBindings()).hasSize(2);
        assertThat(result.finalState().budget().usedSemanticCalls()).isEqualTo(2);
    }

    @Test
    void retryPolicyReceivesTheTotalCallsAlreadyMadeForTheNeed() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .registerSequence(
                        orders,
                        ordersRevision,
                        partial(orders, ordersRevision, "orders-partial"),
                        timeout(true));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-partial-timeout-call-limit", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.SEMANTIC_UNAVAILABLE);
        assertThat(semanticPort.queries()).hasSize(2);
        assertThat(result.finalState().budget().usedSemanticCalls()).isEqualTo(2);
    }

    @Test
    void exhaustedSemanticCallBudgetConcludesBeforeAnotherExternalCall() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, partial(orders, ordersRevision, "orders-partial"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-semantic-budget",
                List.of(orders),
                List.of(need),
                AnalysisBudget.of(20, 1)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.BUDGET_EXHAUSTED);
        assertThat(semanticPort.queries()).hasSize(1);
    }

    @Test
    void exhaustedStepBudgetConcludesBeforeAnotherExternalCall() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, partial(orders, ordersRevision, "orders-partial"));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-step-budget",
                List.of(orders),
                List.of(need),
                AnalysisBudget.of(2, 10)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.BUDGET_EXHAUSTED);
        assertThat(semanticPort.queries()).hasSize(1);
    }

    @Test
    void semanticDiscoveryAfterTheFinalStepConcludesBeforeItsRevisionCall() {
        RepositoryId orders = repository("orders");
        RepositoryId notifications = repository("notifications");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed ordersNeed = need("need-a-orders", orders);
        InformationNeed notificationsNeed = need("need-b-notifications", notifications);
        EvidenceRef ordersEvidence = evidence(orders, ordersRevision, "orders-evidence");
        RepositoryDiscovery discovery = new RepositoryDiscovery(
                notifications, "orders publish notification", ordersEvidence);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision)
                .register(notifications, revision("notifications-a"));
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(ordersRevision),
                        List.of(ordersEvidence),
                        List.of(discovery),
                        Optional.empty()));
        Fixture fixture = fixture(revisions, semanticPort);

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-discovery-step-budget",
                List.of(orders),
                List.of(ordersNeed, notificationsNeed),
                AnalysisBudget.of(2, 10)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.BUDGET_EXHAUSTED);
        assertThat(revisions.calls()).containsExactly(orders);
        assertThat(semanticPort.queries()).hasSize(1);
    }

    @Test
    void cancellationBeforeFirstSemanticCallConcludesTheAttemptAndRun() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort();
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new SequenceCancellationPort(false, false, true));

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-cancel-before-call", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.CANCELLED);
        assertThat(result.run().currentAttempt().outcome()).contains(AttemptOutcome.CANCELLED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.CANCELLED);
        assertThat(fixture.transitions().events())
                .filteredOn(AnalysisEvent.AttemptConcluded.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AnalysisEvent.AttemptConcluded) event).outcome())
                        .isEqualTo(AttemptOutcome.CANCELLED));
        assertThat(semanticPort.queries()).isEmpty();
        assertThat(result.finalState().budget().usedSemanticCalls()).isZero();
    }

    @Test
    void cancellationBetweenRetryCallsPreventsTheRetry() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, timeout(true));
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new SequenceCancellationPort(false, false, false, true));

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-cancel-before-retry", List.of(orders), List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.CANCELLED);
        assertThat(result.run().currentAttempt().outcome()).contains(AttemptOutcome.CANCELLED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.CANCELLED);
        assertThat(semanticPort.queries()).hasSize(1);
        assertThat(result.finalState().budget().usedSemanticCalls()).isEqualTo(1);
    }

    @Test
    void evidenceAcceptanceCommitFailureDoesNotExposeCandidateEvidenceOrTerminalState() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, success(orders, ordersRevision, "orders-evidence"));
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(6);
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new FakeCancellationAdapter(),
                transitions);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-evidence-acceptance-failure", List.of(orders), List.of(need))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.lastCommittedState().status()).isEqualTo(AnalysisStatus.EXECUTING);
                    assertThat(exception.lastCommittedState().evidenceBindings()).isEmpty();
                });
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.EvidenceAccepted.class::isInstance);
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.AttemptConcluded.class::isInstance);
    }

    @Test
    void needResolutionCommitFailureReportsTheAcceptedEvidenceStateWithoutATerminalEvent() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, success(orders, ordersRevision, "orders-evidence"));
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(7);
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new FakeCancellationAdapter(),
                transitions);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-transition-failure", List.of(orders), List.of(need))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.lastCommittedState().status()).isEqualTo(AnalysisStatus.EXECUTING);
                    assertThat(exception.lastCommittedState().evidenceBindings()).hasSize(1);
                });
        assertThat(fixture.transitions().events())
                .filteredOn(AnalysisEvent.EvidenceAccepted.class::isInstance)
                .singleElement()
                .isInstanceOf(AnalysisEvent.EvidenceAccepted.class);
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.NeedResolved.class::isInstance);
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.AttemptConcluded.class::isInstance);
    }

    @Test
    void initialRevisionBudgetCommitFailureReportsTheCommittedScopeState() {
        RepositoryId orders = repository("orders");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, revision("orders-a"));
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(2);
        Fixture fixture = fixture(revisions, new RecordingSemanticPort(), new FakeCancellationAdapter(), transitions);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-initial-budget-commit-failure", List.of(orders), List.of(need))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.lastCommittedState().status()).isEqualTo(AnalysisStatus.REVISION_PINNING);
                    assertThat(exception.lastCommittedState().repositoryScope().repositoryIds())
                            .containsExactly(orders);
                });
        assertThat(fixture.transitions().events())
                .singleElement()
                .isInstanceOf(AnalysisEvent.ScopeResolved.class);
        assertThat(revisions.calls()).isEmpty();
    }

    @Test
    void initialScopeCommitFailureReportsTheInitialRevisionZeroState() {
        RepositoryId orders = repository("orders");
        InformationNeed need = need("need-orders", orders);
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(1);
        Fixture fixture = fixture(
                new RecordingRevisionPort().register(orders, revision("orders-a")),
                new RecordingSemanticPort(),
                new FakeCancellationAdapter(),
                transitions);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-initial-scope-commit-failure", List.of(orders), List.of(need))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.lastCommittedState().stateRevision()).isZero();
                    assertThat(exception.lastCommittedState().status()).isEqualTo(AnalysisStatus.RECEIVED);
                });
        assertThat(fixture.transitions().events()).isEmpty();
    }

    @Test
    void initialRevisionPreparationExhaustsTheBudgetBeforeTheSecondRevisionPortCall() {
        RepositoryId alpha = repository("alpha");
        RepositoryId zeta = repository("zeta");
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(alpha, revision("alpha-a"))
                .register(zeta, revision("zeta-a"));
        Fixture fixture = fixture(revisions, new RecordingSemanticPort());

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-initial-preparation-budget",
                List.of(zeta, alpha),
                List.of(need("need-alpha", alpha), need("need-zeta", zeta)),
                AnalysisBudget.of(1, 10)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.INCONCLUSIVE);
        assertThat(result.run().currentAttempt().outcome()).contains(AttemptOutcome.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.BUDGET_EXHAUSTED);
        assertThat(revisions.calls()).containsExactly(alpha);
    }

    @Test
    void cancellationBetweenInitialRevisionProbesPreventsTheLaterRevisionCall() {
        RepositoryId alpha = repository("alpha");
        RepositoryId zeta = repository("zeta");
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(alpha, revision("alpha-a"))
                .register(zeta, revision("zeta-a"));
        Fixture fixture = fixture(
                revisions,
                new RecordingSemanticPort(),
                new SequenceCancellationPort(false, true));

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-cancel-between-initial-probes",
                List.of(zeta, alpha),
                List.of(need("need-alpha", alpha))));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.CANCELLED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.CANCELLED);
        assertThat(revisions.calls()).containsExactly(alpha);
        assertThat(result.finalState().budget().usedSemanticCalls()).isZero();
    }

    @Test
    void initialPreparationCancellationCheckFailureReportsTheCommittedScopeState() {
        RepositoryId orders = repository("orders");
        IllegalStateException expected = new IllegalStateException("cancellation adapter failed");
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, revision("orders-a"));
        Fixture fixture = fixture(
                revisions,
                new RecordingSemanticPort(),
                new FailingAtCancellationCheckPort(1, expected));

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-initial-cancellation-check-failure",
                List.of(orders),
                List.of(need("need-orders", orders)))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.getCause()).hasCause(expected);
                    assertThat(exception.lastCommittedState().stateRevision()).isEqualTo(1);
                    assertThat(exception.lastCommittedState().attemptId())
                            .isEqualTo(new AnalysisAttemptId("attempt-1"));
                    assertThat(exception.lastCommittedState().repositoryScope().contains(orders)).isTrue();
                    assertThat(exception.lastCommittedState().budget().usedSteps()).isZero();
                });
        assertThat(revisions.calls()).isEmpty();
    }

    @Test
    void cancellationBetweenReplacementRevisionProbesPreventsTheLaterRevisionCall() {
        RepositoryId alpha = repository("alpha");
        RepositoryId zeta = repository("zeta");
        RepositoryRevision alphaA = revision("alpha-a");
        RepositoryRevision alphaB = revision("alpha-b");
        RepositoryRevision zetaA = revision("zeta-a");
        InformationNeed need = need("need-alpha", alpha);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .registerSequence(alpha, alphaA, alphaB)
                .register(zeta, zetaA);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(alpha, alphaA, revisionMismatch(alphaB));
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new SequenceCancellationPort(false, false, false, false, false, false, true));

        AnalysisExecutionResult result = fixture.coordinator().execute(command(
                "run-cancel-between-replacement-probes",
                List.of(zeta, alpha),
                List.of(need)));

        assertThat(result.run().outcome()).contains(AnalysisOutcome.CANCELLED);
        assertThat(result.reason()).isEqualTo(AnalysisTerminationReason.CANCELLED);
        assertThat(revisions.calls()).containsExactly(alpha, zeta, alpha);
        assertThat(semanticPort.queries()).hasSize(1);
        assertThat(result.finalState().budget().usedSemanticCalls()).isZero();
    }

    @Test
    void replacementPreparationCancellationCheckFailureReportsTheReplacementScopeState() {
        RepositoryId orders = repository("orders");
        RepositoryRevision revisionA = revision("orders-a");
        RepositoryRevision revisionB = revision("orders-b");
        IllegalStateException expected = new IllegalStateException("cancellation adapter failed");
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .registerSequence(orders, revisionA, revisionB);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, revisionA, revisionMismatch(revisionB));
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new FailingAtCancellationCheckPort(5, expected));

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-replacement-cancellation-check-failure",
                List.of(orders),
                List.of(need("need-orders", orders)))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.getCause()).hasCause(expected);
                    assertThat(exception.lastCommittedState().stateRevision()).isEqualTo(1);
                    assertThat(exception.lastCommittedState().attemptId())
                            .isEqualTo(new AnalysisAttemptId("attempt-2"));
                    assertThat(exception.lastCommittedState().repositoryScope().contains(orders)).isTrue();
                    assertThat(exception.lastCommittedState().budget().usedSteps()).isZero();
                });
        assertThat(revisions.calls()).containsExactly(orders);
    }

    @Test
    void preparationFailureConclusionCommitFailureReportsThePreConclusionState() {
        RepositoryId orders = repository("orders");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort().registerFailure(
                orders,
                new SemanticFailure(SemanticFailureCode.ENGINE_UNAVAILABLE, "revision service unavailable", false));
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(3);
        Fixture fixture = fixture(revisions, new RecordingSemanticPort(), new FakeCancellationAdapter(), transitions);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-preparation-conclusion-failure", List.of(orders), List.of(need))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.lastCommittedState().status()).isEqualTo(AnalysisStatus.REVISION_PINNING);
                    assertThat(exception.lastCommittedState().budget().usedSteps()).isEqualTo(1);
                });
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.AttemptConcluded.class::isInstance);
    }

    @Test
    void discoveredRevisionPinFailureReportsTheCommittedProbeBudgetState() {
        RepositoryId orders = repository("orders");
        RepositoryId notifications = repository("notifications");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed ordersNeed = need("need-a-orders", orders);
        InformationNeed notificationsNeed = need("need-b-notifications", notifications);
        EvidenceRef ordersEvidence = evidence(orders, ordersRevision, "orders-evidence");
        RepositoryDiscovery discovery = new RepositoryDiscovery(
                notifications, "orders publish notification", ordersEvidence);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision)
                .register(notifications, revision("notifications-a"));
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, ordersRevision, new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(ordersRevision),
                        List.of(ordersEvidence),
                        List.of(discovery),
                        Optional.empty()));
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(11);
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new FakeCancellationAdapter(),
                transitions);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-discovery-pin-failure",
                List.of(orders),
                List.of(ordersNeed, notificationsNeed))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.lastCommittedState().budget().usedSteps()).isEqualTo(3);
                    assertThat(exception.lastCommittedState().revisionVector().revisionOf(notifications))
                            .isEmpty();
                });
        assertThat(revisions.calls()).containsExactly(orders, notifications);
        assertThat(fixture.transitions().events().getLast())
                .isInstanceOf(AnalysisEvent.BudgetConsumed.class);
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.AttemptConcluded.class::isInstance);
    }

    @Test
    void restartPreparationFailureReportsTheCommittedReplacementScopeState() {
        RepositoryId orders = repository("orders");
        RepositoryRevision revisionA = revision("orders-a");
        RepositoryRevision revisionB = revision("orders-b");
        InformationNeed need = need("need-orders", orders);
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .registerSequence(orders, revisionA, revisionB);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort()
                .register(orders, revisionA, revisionMismatch(revisionB));
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter()
                .failAtCommit(8);
        Fixture fixture = fixture(
                revisions,
                semanticPort,
                new FakeCancellationAdapter(),
                transitions);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-restart-preparation-failure", List.of(orders), List.of(need))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.lastCommittedState().attemptId())
                            .isEqualTo(new AnalysisAttemptId("attempt-2"));
                    assertThat(exception.lastCommittedState().status())
                            .isEqualTo(AnalysisStatus.REVISION_PINNING);
                    assertThat(exception.lastCommittedState().budget().usedSteps()).isZero();
                });
        assertThat(revisions.calls()).containsExactly(orders);
        assertThat(fixture.transitions().events().getLast())
                .isInstanceOf(AnalysisEvent.ScopeResolved.class);
    }

    @Test
    void semanticPortFailureReportsTheBudgetConsumedStateWithoutTerminalEvent() {
        RepositoryId orders = repository("orders");
        RepositoryRevision ordersRevision = revision("orders-a");
        InformationNeed need = need("need-orders", orders);
        IllegalStateException expected = new IllegalStateException("semantic transport failed");
        RecordingRevisionPort revisions = new RecordingRevisionPort()
                .register(orders, ordersRevision);
        RecordingSemanticPort semanticPort = new RecordingSemanticPort().failWith(expected);
        Fixture fixture = fixture(revisions, semanticPort);

        assertThatThrownBy(() -> fixture.coordinator().execute(command(
                "run-semantic-port-failure", List.of(orders), List.of(need))))
                .isInstanceOfSatisfying(AnalysisExecutionException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(AnalysisTerminationReason.RUNTIME_FAILURE);
                    assertThat(exception.getCause()).isSameAs(expected);
                    assertThat(exception.lastCommittedState().status()).isEqualTo(AnalysisStatus.EXECUTING);
                    assertThat(exception.lastCommittedState().budget().usedSemanticCalls()).isEqualTo(1);
                });
        assertThat(fixture.transitions().events())
                .noneMatch(AnalysisEvent.AttemptConcluded.class::isInstance);
    }

    private Fixture fixture(RecordingRevisionPort revisions, RecordingSemanticPort semanticPort) {
        return fixture(revisions, semanticPort, new FakeCancellationAdapter());
    }

    private Fixture fixture(
            RecordingRevisionPort revisions,
            RecordingSemanticPort semanticPort,
            AnalysisCancellationPort cancellationPort) {
        return fixture(
                revisions,
                semanticPort,
                cancellationPort,
                new InMemoryAnalysisTransitionAdapter());
    }

    private Fixture fixture(
            RecordingRevisionPort revisions,
            RecordingSemanticPort semanticPort,
            AnalysisCancellationPort cancellationPort,
            InMemoryAnalysisTransitionAdapter transitions) {
        revisions.observe(semanticPort);
        semanticPort.observe(revisions);
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), transitions);
        AttemptLifecycleManager lifecycleManager = new AttemptLifecycleManager(
                committer,
                revisions,
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-2")));
        return new Fixture(new BoundedAnalysisCoordinator(
                lifecycleManager,
                new DefaultGoalEvaluator(),
                new InformationNeedPlanner(),
                registry(),
                new SemanticRetryPolicy(),
                new SemanticResultHandler(committer),
                new NoProgressPolicy(2),
                committer,
                semanticPort,
                cancellationPort), transitions);
    }

    private AnalysisExecutionCommand command(
            String runId,
            List<RepositoryId> repositories,
            List<InformationNeed> needs) {
        return command(runId, repositories, needs, AnalysisBudget.of(20, 10));
    }

    private AnalysisExecutionCommand command(
            String runId,
            List<RepositoryId> repositories,
            List<InformationNeed> needs,
            AnalysisBudget budget) {
        return new AnalysisExecutionCommand(
                new AnalysisRunId(runId),
                new AnalysisAttemptId("attempt-1"),
                RepositoryScope.of(repositories.stream()
                        .map(repository -> new RepositorySelection(
                                repository,
                                "coordinator test scope",
                                true,
                                RepositoryDiscoverySource.USER))
                        .toList()),
                needs,
                new Goal("complete coordinator test", needs.stream()
                        .map(InformationNeed::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())),
                budget);
    }

    private InformationNeed need(String id, RepositoryId repository) {
        return new InformationNeed(
                new InformationNeedId(id),
                InformationNeedType.METHOD_IMPLEMENTATION,
                "Resolve " + id,
                true,
                List.of(repository),
                List.of(target(repository)));
    }

    private SemanticCapabilityRegistry registry() {
        return new SemanticCapabilityRegistry(List.of(new SemanticCapability(
                "method-implementation",
                "v1",
                Set.of(InformationNeedType.METHOD_IMPLEMENTATION),
                "input-v1",
                "output-v1",
                true,
                true,
                1,
                Duration.ofSeconds(1),
                1)));
    }

    private SemanticQueryResult success(
            RepositoryId repository,
            RepositoryRevision repositoryRevision,
            String artifactDigest) {
        return new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(repositoryRevision),
                List.of(evidence(repository, repositoryRevision, artifactDigest)),
                List.of(),
                Optional.empty());
    }

    private SemanticQueryResult failure(SemanticFailureCode failureCode, String message) {
        return new SemanticQueryResult(
                SemanticResultStatus.FAILED,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(failureCode, message, false)));
    }

    private SemanticQueryResult revisionMismatch(RepositoryRevision analyzedRevision) {
        return new SemanticQueryResult(
                SemanticResultStatus.REVISION_MISMATCH,
                Optional.of(analyzedRevision),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.REVISION_MISMATCH,
                        "repository revision changed",
                        false)));
    }

    private SemanticQueryResult timeout(boolean retryable) {
        return new SemanticQueryResult(
                SemanticResultStatus.TIMEOUT,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.TIMEOUT,
                        "semantic call timed out",
                        retryable)));
    }

    private SemanticQueryResult partial(
            RepositoryId repository,
            RepositoryRevision repositoryRevision,
            String artifactDigest) {
        return new SemanticQueryResult(
                SemanticResultStatus.PARTIAL,
                Optional.of(repositoryRevision),
                List.of(evidence(repository, repositoryRevision, artifactDigest)),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.PARTIAL_RESULT,
                        "semantic result remains partial",
                        false)));
    }

    private EvidenceRef evidence(
            RepositoryId repository,
            RepositoryRevision repositoryRevision,
            String artifactDigest) {
        return new EvidenceRef(
                "semantic-service",
                repository,
                repositoryRevision,
                target(repository),
                1.0,
                List.of(),
                new ArtifactRef("sha256:" + artifactDigest));
    }

    private SemanticTarget target(RepositoryId repository) {
        return new SemanticTarget(SemanticTargetKind.REPOSITORY, repository.value(), Optional.empty());
    }

    private RepositoryId repository(String value) {
        return new RepositoryId(value);
    }

    private RepositoryRevision revision(String value) {
        return new RepositoryRevision(value);
    }

    private void assertThatAcceptedEvidenceMatchesFinalVector(AnalysisExecutionResult result) {
        assertThat(result.finalState().evidenceBindings()).allSatisfy(binding -> assertThat(
                result.finalState().revisionVector().revisionOf(binding.evidenceRef().repositoryId()))
                .contains(binding.evidenceRef().repositoryRevision()));
    }

    private record Fixture(
            BoundedAnalysisCoordinator coordinator,
            InMemoryAnalysisTransitionAdapter transitions) {
    }

    private static final class RecordingRevisionPort implements RepositoryRevisionPort {

        private final Map<RepositoryId, ArrayDeque<RepositoryRevisionResult>> revisions = new TreeMap<>();
        private final List<RepositoryId> calls = new ArrayList<>();
        private RecordingSemanticPort semanticPort;

        private RecordingRevisionPort register(RepositoryId repository, RepositoryRevision repositoryRevision) {
            return registerSequence(repository, repositoryRevision);
        }

        private RecordingRevisionPort registerSequence(
                RepositoryId repository,
                RepositoryRevision... repositoryRevisions) {
            ArrayDeque<RepositoryRevisionResult> results = new ArrayDeque<>();
            for (RepositoryRevision repositoryRevision : repositoryRevisions) {
                results.add(RepositoryRevisionResult.ready(repositoryRevision));
            }
            revisions.put(repository, results);
            return this;
        }

        private RecordingRevisionPort registerFailure(
                RepositoryId repository,
                SemanticFailure semanticFailure) {
            revisions.put(
                    repository,
                    new ArrayDeque<>(List.of(RepositoryRevisionResult.unavailable(semanticFailure))));
            return this;
        }

        private RecordingRevisionPort observe(RecordingSemanticPort observedSemanticPort) {
            semanticPort = observedSemanticPort;
            return this;
        }

        @Override
        public RepositoryRevisionResult currentRevision(RepositoryId repository) {
            calls.add(repository);
            ArrayDeque<RepositoryRevisionResult> results = revisions.get(repository);
            RepositoryRevisionResult result = results.removeFirst();
            results.addLast(result);
            return result;
        }

        private List<RepositoryId> calls() {
            return List.copyOf(calls);
        }

        private List<RepositoryId> callsBeforeFirstSemanticQuery() {
            return calls.subList(0, semanticPort.firstQueryRevisionCallCount());
        }
    }

    private static final class RecordingSemanticPort implements SemanticQueryPort {

        private final Map<QueryKey, ArrayDeque<SemanticQueryResult>> results = new TreeMap<>();
        private final List<SemanticQuery> queries = new ArrayList<>();
        private Optional<RuntimeException> queryFailure = Optional.empty();
        private int firstQueryRevisionCallCount;
        private RecordingRevisionPort revisionPort;

        private RecordingSemanticPort register(
                RepositoryId repository,
                RepositoryRevision repositoryRevision,
                SemanticQueryResult result) {
            return registerSequence(repository, repositoryRevision, result);
        }

        private RecordingSemanticPort registerSequence(
                RepositoryId repository,
                RepositoryRevision repositoryRevision,
                SemanticQueryResult... sequence) {
            results.put(
                    new QueryKey(repository, repositoryRevision),
                    new ArrayDeque<>(List.of(sequence)));
            return this;
        }

        private RecordingSemanticPort failWith(RuntimeException exception) {
            queryFailure = Optional.of(exception);
            return this;
        }

        private RecordingSemanticPort observe(RecordingRevisionPort observedRevisionPort) {
            revisionPort = observedRevisionPort;
            return this;
        }

        @Override
        public SemanticQueryResult query(SemanticQuery query) {
            if (queries.isEmpty()) {
                firstQueryRevisionCallCount = revisionPort.calls().size();
            }
            queries.add(query);
            if (queryFailure.isPresent()) {
                throw queryFailure.orElseThrow();
            }
            ArrayDeque<SemanticQueryResult> sequence = results.get(new QueryKey(
                    query.repositoryId(),
                    query.expectedRevision()));
            SemanticQueryResult result = sequence.removeFirst();
            sequence.addLast(result);
            return result;
        }

        private List<SemanticQuery> queries() {
            return List.copyOf(queries);
        }

        private int firstQueryRevisionCallCount() {
            return firstQueryRevisionCallCount;
        }
    }

    private record QueryKey(RepositoryId repository, RepositoryRevision revision) implements Comparable<QueryKey> {

        @Override
        public int compareTo(QueryKey other) {
            int repositoryComparison = repository.compareTo(other.repository());
            return repositoryComparison == 0
                    ? revision.value().compareTo(other.revision().value())
                    : repositoryComparison;
        }
    }

    private static final class SequenceCancellationPort implements AnalysisCancellationPort {

        private final List<Boolean> results;
        private int position;

        private SequenceCancellationPort(Boolean... results) {
            this.results = List.of(results);
        }

        @Override
        public boolean isCancellationRequested(AnalysisRunId runId) {
            boolean result = results.get(Math.min(position, results.size() - 1));
            position++;
            return result;
        }

        private int checkCount() {
            return position;
        }
    }

    private static final class FailingAtCancellationCheckPort implements AnalysisCancellationPort {

        private final int failingCheck;
        private final RuntimeException failure;
        private int checkCount;

        private FailingAtCancellationCheckPort(int failingCheck, RuntimeException failure) {
            this.failingCheck = failingCheck;
            this.failure = failure;
        }

        @Override
        public boolean isCancellationRequested(AnalysisRunId runId) {
            checkCount++;
            if (checkCount == failingCheck) {
                throw failure;
            }
            return false;
        }
    }
}
