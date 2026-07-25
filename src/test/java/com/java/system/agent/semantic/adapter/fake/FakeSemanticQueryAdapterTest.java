package com.java.system.agent.semantic.adapter.fake;

import com.java.system.agent.runtime.domain.ArtifactRef;
import com.java.system.agent.runtime.domain.EvidenceRef;
import com.java.system.agent.runtime.domain.InformationNeed;
import com.java.system.agent.runtime.domain.InformationNeedId;
import com.java.system.agent.runtime.domain.InformationNeedType;
import com.java.system.agent.runtime.domain.RepositoryId;
import com.java.system.agent.runtime.domain.RepositoryRevision;
import com.java.system.agent.runtime.domain.SemanticTarget;
import com.java.system.agent.runtime.domain.SemanticTargetKind;
import com.java.system.agent.runtime.port.out.SemanticFailure;
import com.java.system.agent.runtime.port.out.SemanticFailureCode;
import com.java.system.agent.runtime.port.out.SemanticQuery;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;
import com.java.system.agent.runtime.port.out.SemanticResultStatus;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FakeSemanticQueryAdapterTest {

    @Test
    void returnsOnlyTheExplicitlyRegisteredRevisionBoundScenario() {
        SemanticQuery query = query();
        EvidenceRef evidence = evidence(new RepositoryRevision("ord-456"));
        SemanticQueryResult success = new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(new RepositoryRevision("ord-456")),
                List.of(evidence),
                List.of(),
                Optional.empty());
        FakeSemanticQueryAdapter adapter = new FakeSemanticQueryAdapter().register(query, success);

        SemanticQueryResult result = adapter.query(query);

        assertThat(result).isEqualTo(success);
        assertThat(result.analyzedRevision()).contains(new RepositoryRevision("ord-456"));
    }

    @Test
    void returnsRegisteredResponsesInOrderThenRetainsTheFinalResponse() {
        SemanticQuery query = query();
        RepositoryRevision revision = new RepositoryRevision("ord-456");
        SemanticQueryResult notReady = failure(
                SemanticResultStatus.NOT_READY, SemanticFailureCode.NOT_READY, true);
        SemanticQueryResult success = new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(revision),
                List.of(evidence(revision)),
                List.of(),
                Optional.empty());
        FakeSemanticQueryAdapter adapter = new FakeSemanticQueryAdapter()
                .registerSequence(query, notReady, success);

        assertThat(adapter.query(query)).isEqualTo(notReady);
        assertThat(adapter.query(query)).isEqualTo(success);
        assertThat(adapter.query(query)).isEqualTo(success);
    }

    @Test
    void reRegisteringAQueryReplacesItsSequenceAndResetsItsPosition() {
        SemanticQuery query = query();
        RepositoryRevision revision = new RepositoryRevision("ord-456");
        SemanticQueryResult notReady = failure(
                SemanticResultStatus.NOT_READY, SemanticFailureCode.NOT_READY, true);
        SemanticQueryResult timeout = failure(
                SemanticResultStatus.TIMEOUT, SemanticFailureCode.TIMEOUT, true);
        SemanticQueryResult success = new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(revision),
                List.of(evidence(revision)),
                List.of(),
                Optional.empty());
        FakeSemanticQueryAdapter adapter = new FakeSemanticQueryAdapter()
                .registerSequence(query, notReady, success);
        assertThat(adapter.query(query)).isEqualTo(notReady);

        adapter.registerSequence(query, timeout, success);

        assertThat(adapter.query(query)).isEqualTo(timeout);
        assertThat(adapter.query(query)).isEqualTo(success);
        assertThat(adapter.query(query)).isEqualTo(success);
    }

    @Test
    void rejectsUnregisteredQueryInsteadOfUsingHiddenFallback() {
        FakeSemanticQueryAdapter adapter = new FakeSemanticQueryAdapter();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.query(query()))
                .withMessageContaining("scenario");
    }

    @Test
    void representsPartialAmbiguousMismatchAndOperationalFailuresExplicitly() {
        RepositoryRevision analyzed = new RepositoryRevision("ord-789");
        SemanticQueryResult partial = new SemanticQueryResult(
                SemanticResultStatus.PARTIAL,
                Optional.of(analyzed),
                List.of(evidence(analyzed)),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.PARTIAL_RESULT,
                        "Some dynamic calls remain unresolved",
                        false)));
        SemanticQueryResult ambiguous = failure(
                SemanticResultStatus.AMBIGUOUS,
                SemanticFailureCode.AMBIGUOUS_TARGET,
                false);
        SemanticQueryResult mismatch = new SemanticQueryResult(
                SemanticResultStatus.REVISION_MISMATCH,
                Optional.of(analyzed),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.REVISION_MISMATCH,
                        "Analyzed revision differs from expected revision",
                        true)));
        List<SemanticQueryResult> operationalFailures = List.of(
                failure(SemanticResultStatus.NOT_READY, SemanticFailureCode.NOT_READY, true),
                failure(SemanticResultStatus.TIMEOUT, SemanticFailureCode.TIMEOUT, true),
                failure(SemanticResultStatus.FORBIDDEN, SemanticFailureCode.FORBIDDEN, false),
                failure(
                        SemanticResultStatus.CAPABILITY_MISSING,
                        SemanticFailureCode.CAPABILITY_MISSING,
                        false));

        assertThat(partial.evidence()).hasSize(1);
        assertThat(ambiguous.analyzedRevision()).isEmpty();
        assertThat(mismatch.analyzedRevision()).contains(analyzed);
        assertThat(operationalFailures)
                .allSatisfy(result -> {
                    assertThat(result.analyzedRevision()).isEmpty();
                    assertThat(result.evidence()).isEmpty();
                    assertThat(result.failure()).isPresent();
                });
    }

    @Test
    void semanticQueryContractHasNoTransportEscapeHatch() {
        List<String> componentNames = Arrays.stream(SemanticQuery.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .containsExactly(
                        "capabilityName",
                        "informationNeed",
                        "semanticTarget",
                        "repositoryId",
                        "expectedRevision")
                .doesNotContain("url", "headers", "rawJson", "httpMethod");
    }

    @Test
    void retainsSourceEvidenceForCrossRepositoryDiscovery() {
        EvidenceRef sourceEvidence = evidence(new RepositoryRevision("ord-456"));
        RepositoryDiscovery discovery = new RepositoryDiscovery(
                new RepositoryId("notification-service"),
                "OrderCreated consumer was resolved from semantic evidence",
                sourceEvidence);

        SemanticQueryResult result = new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(new RepositoryRevision("ord-456")),
                List.of(sourceEvidence),
                List.of(discovery),
                Optional.empty());

        assertThat(result.repositoryDiscoveries()).containsExactly(discovery);
    }

    @Test
    void rejectsSuccessThatAlsoCarriesAFailure() {
        RepositoryRevision analyzed = new RepositoryRevision("ord-456");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(analyzed),
                        List.of(evidence(analyzed)),
                        List.of(),
                        Optional.of(new SemanticFailure(
                                SemanticFailureCode.PARTIAL_RESULT,
                                "Unexpected failure on success",
                                false))))
                .withMessageContaining("success");
    }

    @Test
    void rejectsPartialResultWithoutANormalizedFailure() {
        RepositoryRevision analyzed = new RepositoryRevision("ord-456");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SemanticQueryResult(
                        SemanticResultStatus.PARTIAL,
                        Optional.of(analyzed),
                        List.of(evidence(analyzed)),
                        List.of(),
                        Optional.empty()))
                .withMessageContaining("partial");
    }

    @Test
    void rejectsEvidenceFromADifferentRevisionThanTheAnalyzedRevision() {
        RepositoryRevision analyzed = new RepositoryRevision("ord-456");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(analyzed),
                        List.of(evidence(new RepositoryRevision("ord-789"))),
                        List.of(),
                        Optional.empty()))
                .withMessageContaining("revision");
    }

    private SemanticQueryResult failure(
            SemanticResultStatus status,
            SemanticFailureCode code,
            boolean retryable) {
        return new SemanticQueryResult(
                status,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(code, "Configured fake failure", retryable)));
    }

    private SemanticQuery query() {
        RepositoryId repositoryId = new RepositoryId("order-service");
        SemanticTarget target = new SemanticTarget(
                SemanticTargetKind.ROUTE,
                "POST /orders",
                Optional.empty());
        InformationNeed need = new InformationNeed(
                new InformationNeedId("need-entry-point"),
                InformationNeedType.ENTRY_POINT,
                "Locate the order entry point",
                true,
                List.of(repositoryId),
                List.of(target));
        return new SemanticQuery(
                "entry-point/v1",
                need,
                target,
                repositoryId,
                new RepositoryRevision("ord-456"));
    }

    private EvidenceRef evidence(RepositoryRevision revision) {
        return new EvidenceRef(
                "java-semantic-service",
                new RepositoryId("order-service"),
                revision,
                new SemanticTarget(
                        SemanticTargetKind.SYMBOL,
                        "com.example.OrderController#create",
                        Optional.empty()),
                1.0,
                List.of(),
                new ArtifactRef("sha256:entry-point"));
    }
}
