package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisOutcome;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.ArtifactRef;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

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

    private Fixture fixture(RecordingRevisionPort revisions, RecordingSemanticPort semanticPort) {
        return fixture(revisions, semanticPort, new FakeCancellationAdapter());
    }

    private Fixture fixture(
            RecordingRevisionPort revisions,
            RecordingSemanticPort semanticPort,
            AnalysisCancellationPort cancellationPort) {
        revisions.observe(semanticPort);
        semanticPort.observe(revisions);
        InMemoryAnalysisTransitionAdapter transitions = new InMemoryAnalysisTransitionAdapter();
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), transitions);
        AttemptLifecycleManager lifecycleManager = new AttemptLifecycleManager(
                committer, revisions, new FakeAttemptIdGenerator());
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
                AnalysisBudget.of(20, 10));
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

        private final Map<RepositoryId, RepositoryRevisionResult> revisions = new TreeMap<>();
        private final List<RepositoryId> calls = new ArrayList<>();
        private RecordingSemanticPort semanticPort;

        private RecordingRevisionPort register(RepositoryId repository, RepositoryRevision repositoryRevision) {
            revisions.put(repository, RepositoryRevisionResult.ready(repositoryRevision));
            return this;
        }

        private RecordingRevisionPort registerFailure(
                RepositoryId repository,
                SemanticFailure semanticFailure) {
            revisions.put(repository, RepositoryRevisionResult.unavailable(semanticFailure));
            return this;
        }

        private RecordingRevisionPort observe(RecordingSemanticPort observedSemanticPort) {
            semanticPort = observedSemanticPort;
            return this;
        }

        @Override
        public RepositoryRevisionResult currentRevision(RepositoryId repository) {
            calls.add(repository);
            return revisions.get(repository);
        }

        private List<RepositoryId> calls() {
            return List.copyOf(calls);
        }

        private List<RepositoryId> callsBeforeFirstSemanticQuery() {
            return calls.subList(0, semanticPort.firstQueryRevisionCallCount());
        }
    }

    private static final class RecordingSemanticPort implements SemanticQueryPort {

        private final Map<QueryKey, SemanticQueryResult> results = new TreeMap<>();
        private final List<SemanticQuery> queries = new ArrayList<>();
        private int firstQueryRevisionCallCount;
        private RecordingRevisionPort revisionPort;

        private RecordingSemanticPort register(
                RepositoryId repository,
                RepositoryRevision repositoryRevision,
                SemanticQueryResult result) {
            results.put(new QueryKey(repository, repositoryRevision), result);
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
            return results.get(new QueryKey(query.repositoryId(), query.expectedRevision()));
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
}
