package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.ArtifactRef;
import com.java.system.agent.analysis.domain.EvidenceRef;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.InformationNeedType;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryDiscoverySource;
import com.java.system.agent.analysis.domain.RepositoryRevision;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RepositorySelection;
import com.java.system.agent.analysis.domain.SemanticTarget;
import com.java.system.agent.analysis.domain.SemanticTargetKind;
import com.java.system.agent.analysis.port.out.RepositoryDiscovery;
import com.java.system.agent.analysis.port.out.SemanticFailure;
import com.java.system.agent.analysis.port.out.SemanticFailureCode;
import com.java.system.agent.analysis.port.out.SemanticQueryResult;
import com.java.system.agent.analysis.port.out.SemanticResultStatus;
import com.java.system.agent.runtime.adapter.fake.InMemoryAnalysisTransitionAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticResultHandlerTest {

    @Test
    void successAcceptsEvidenceBeforeExpandingScopeAndThenResolvesTheNeed() {
        Fixture fixture = fixture();
        InformationNeed discoveredNeed = discoveredNeed();
        AnalysisState state = register(fixture, fixture.state(), discoveredNeed);
        EvidenceRef evidence = evidence(ORDER_REVISION, "sha256:success");
        RepositoryDiscovery discovery = discovery(evidence);

        SemanticStepResult result = fixture.handler().handle(
                state,
                mainNeed(),
                success(List.of(evidence), List.of(discovery)),
                false);

        assertThat(result.disposition()).isEqualTo(SemanticStepDisposition.PROGRESSED);
        assertThat(result.failure()).isEmpty();
        assertThat(result.discoveries()).containsExactly(discovery);
        assertThat(result.state().resolvedNeedIds()).contains(mainNeed().id());
        assertThat(result.state().pendingNeeds()).containsKey(discoveredNeed.id());
        assertThat(result.state().repositoryScope().contains(NOTIFICATION_REPOSITORY)).isTrue();
        assertThat(result.state().revisionVector().revisionOf(NOTIFICATION_REPOSITORY)).isEmpty();
        assertThat(fixture.adapter().events().stream()
                .filter(event -> event instanceof AnalysisEvent.EvidenceAccepted
                        || event instanceof AnalysisEvent.ScopeExpanded
                        || event instanceof AnalysisEvent.NeedResolved)
                .map(event -> event.getClass().getSimpleName())
                .toList())
                .containsExactly(
                        "EvidenceAccepted",
                        "ScopeExpanded",
                        "NeedResolved");
    }

    @Test
    void partialResultAcceptsNewEvidenceAndRepeatedPartialDoesNotCountAsProgress() {
        Fixture fixture = fixture();
        EvidenceRef evidence = evidence(ORDER_REVISION, "sha256:partial");
        SemanticQueryResult partial = partial(List.of(evidence));

        SemanticStepResult first = fixture.handler().handle(
                fixture.state(), mainNeed(), partial, false);
        ProgressFingerprint firstFingerprint = ProgressFingerprint.from(first.state());
        int committedAfterFirst = fixture.adapter().events().size();
        SemanticStepResult repeated = fixture.handler().handle(
                first.state(), mainNeed(), partial, false);

        assertThat(first.disposition()).isEqualTo(SemanticStepDisposition.PARTIAL);
        assertThat(first.failure()).contains(partial.failure().orElseThrow());
        assertThat(first.state().pendingNeeds()).containsKey(mainNeed().id());
        assertThat(first.state().warnings()).extracting(warning -> warning.code())
                .containsExactly("SEMANTIC_PARTIAL_RESULT");
        assertThat(repeated.disposition()).isEqualTo(SemanticStepDisposition.PARTIAL);
        assertThat(repeated.state()).isSameAs(first.state());
        assertThat(ProgressFingerprint.from(repeated.state())).isEqualTo(firstFingerprint);
        assertThat(fixture.adapter().events()).hasSize(committedAfterFirst);
    }

    @Test
    void revisionMismatchLeavesStateAndCommitLogUntouched() {
        Fixture fixture = fixture();
        SemanticQueryResult mismatch = new SemanticQueryResult(
                SemanticResultStatus.REVISION_MISMATCH,
                Optional.of(new RepositoryRevision("order-other")),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.REVISION_MISMATCH, "revision changed", true)));

        SemanticStepResult result = fixture.handler().handle(
                fixture.state(), mainNeed(), mismatch, false);

        assertThat(result.disposition()).isEqualTo(SemanticStepDisposition.STALE);
        assertThat(result.state()).isSameAs(fixture.state());
        assertThat(newEvents(fixture)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("transientResults")
    void transientResultsAreRetryableOnlyWhenCoordinatorAllowsRetry(
            SemanticQueryResult result,
            String warningCode) {
        Fixture retryFixture = fixture();
        Fixture blockedFixture = fixture();

        SemanticStepResult retry = retryFixture.handler().handle(
                retryFixture.state(), mainNeed(), result, true);
        SemanticStepResult blocked = blockedFixture.handler().handle(
                blockedFixture.state(), mainNeed(), result, false);

        assertThat(retry.disposition()).isEqualTo(SemanticStepDisposition.RETRYABLE);
        assertThat(retry.state()).isSameAs(retryFixture.state());
        assertThat(newEvents(retryFixture)).isEmpty();
        assertThat(blocked.disposition()).isEqualTo(SemanticStepDisposition.BLOCKED);
        assertThat(blocked.state().warnings()).extracting(warning -> warning.code())
                .containsExactly(warningCode);
    }

    @ParameterizedTest
    @MethodSource("blockedResults")
    void nonTransientBlockedResultsRecordTheirStableWarningCode(
            SemanticQueryResult result,
            String warningCode) {
        Fixture fixture = fixture();

        SemanticStepResult handled = fixture.handler().handle(
                fixture.state(), mainNeed(), result, true);

        assertThat(handled.disposition()).isEqualTo(SemanticStepDisposition.BLOCKED);
        assertThat(handled.failure()).contains(result.failure().orElseThrow());
        assertThat(handled.state().warnings()).extracting(warning -> warning.code())
                .containsExactly(warningCode);
    }

    @ParameterizedTest
    @MethodSource("failedResults")
    void failedResultsRecordTheirFailureCodeWithoutAcceptingEvidence(
            SemanticQueryResult result,
            String warningCode) {
        Fixture fixture = fixture();

        SemanticStepResult handled = fixture.handler().handle(
                fixture.state(), mainNeed(), result, false);

        assertThat(handled.disposition()).isEqualTo(SemanticStepDisposition.FAILED);
        assertThat(handled.failure()).contains(result.failure().orElseThrow());
        assertThat(handled.state().evidenceBindings()).isEmpty();
        assertThat(handled.state().warnings()).extracting(warning -> warning.code())
                .containsExactly(warningCode);
    }

    @Test
    void stateRevisionMismatchPreventsAllCommits() {
        Fixture fixture = fixture();
        RepositoryRevision mismatchedRevision = new RepositoryRevision("order-other");
        EvidenceRef mismatched = new EvidenceRef(
                "semantic",
                ORDER_REPOSITORY,
                mismatchedRevision,
                target(),
                1.0,
                List.of(),
                new ArtifactRef("sha256:mismatched"));

        SemanticStepResult result = fixture.handler().handle(
                fixture.state(),
                mainNeed(),
                new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(mismatchedRevision),
                        List.of(mismatched),
                        List.of(),
                        Optional.empty()),
                false);

        assertThat(result.disposition()).isEqualTo(SemanticStepDisposition.STALE);
        assertThat(result.state()).isSameAs(fixture.state());
        assertThat(newEvents(fixture)).isEmpty();
    }

    @Test
    void requiresTheExactPendingNeedValueBeforeCommitting() {
        Fixture fixture = fixture();
        InformationNeed changedNeed = new InformationNeed(
                mainNeed().id(),
                InformationNeedType.ENTRY_POINT,
                "different description",
                true,
                List.of(ORDER_REPOSITORY),
                List.of(target()));

        assertThatIllegalArgumentException().isThrownBy(() -> fixture.handler().handle(
                fixture.state(), changedNeed, success(List.of(evidence(ORDER_REVISION, "sha256:need")), List.of()), false));
        assertThat(newEvents(fixture)).isEmpty();
    }

    @Test
    void resultDiscoveriesAreImmutable() {
        Fixture fixture = fixture();
        EvidenceRef evidence = evidence(ORDER_REVISION, "sha256:immutable");
        List<RepositoryDiscovery> discoveries = new ArrayList<>(List.of(discovery(evidence)));
        SemanticStepResult result = new SemanticStepResult(
                fixture.state(),
                SemanticStepDisposition.PROGRESSED,
                Optional.empty(),
                discoveries);
        discoveries.clear();

        assertThat(result.discoveries()).hasSize(1);
        assertThatThrownBy(() -> result.discoveries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void transitionCommitFailuresPropagateUnchanged() {
        Fixture fixture = fixture();
        AnalysisTransitionCommitException expected = new AnalysisTransitionCommitException("commit rejected");
        TransitionCommitter failingCommitter = new TransitionCommitter(
                new DefaultStateReducer(), transition -> {
                    throw expected;
                });
        SemanticResultHandler handler = new SemanticResultHandler(failingCommitter);

        assertThatThrownBy(() -> handler.handle(
                fixture.state(),
                mainNeed(),
                success(List.of(evidence(ORDER_REVISION, "sha256:failure")), List.of()),
                false)).isSameAs(expected);
    }

    private static Stream<Arguments> transientResults() {
        return Stream.of(
                Arguments.of(failure(SemanticResultStatus.NOT_READY, SemanticFailureCode.NOT_READY, true),
                        "SEMANTIC_NOT_READY"),
                Arguments.of(failure(SemanticResultStatus.TIMEOUT, SemanticFailureCode.TIMEOUT, true),
                        "SEMANTIC_TIMEOUT"));
    }

    private static Stream<Arguments> blockedResults() {
        return Stream.of(
                Arguments.of(failure(SemanticResultStatus.AMBIGUOUS, SemanticFailureCode.AMBIGUOUS_TARGET, false),
                        "SEMANTIC_AMBIGUOUS_TARGET"),
                Arguments.of(failure(SemanticResultStatus.FORBIDDEN, SemanticFailureCode.FORBIDDEN, false),
                        "SEMANTIC_FORBIDDEN"),
                Arguments.of(failure(
                        SemanticResultStatus.CAPABILITY_MISSING,
                        SemanticFailureCode.CAPABILITY_MISSING,
                        false), "SEMANTIC_CAPABILITY_MISSING"));
    }

    private static Stream<Arguments> failedResults() {
        return Stream.of(
                Arguments.of(failure(SemanticResultStatus.FAILED, SemanticFailureCode.PROTOCOL_ERROR, false),
                        "SEMANTIC_PROTOCOL_ERROR"),
                Arguments.of(failure(SemanticResultStatus.FAILED, SemanticFailureCode.ENGINE_UNAVAILABLE, false),
                        "SEMANTIC_ENGINE_UNAVAILABLE"),
                Arguments.of(failure(SemanticResultStatus.FAILED, SemanticFailureCode.ENGINE_FAILURE, false),
                        "SEMANTIC_ENGINE_FAILURE"),
                Arguments.of(failure(SemanticResultStatus.FAILED, SemanticFailureCode.REPOSITORY_NOT_FOUND, false),
                        "SEMANTIC_REPOSITORY_NOT_FOUND"));
    }

    private Fixture fixture() {
        InMemoryAnalysisTransitionAdapter adapter = new InMemoryAnalysisTransitionAdapter();
        TransitionCommitter committer = new TransitionCommitter(new DefaultStateReducer(), adapter);
        AnalysisState initial = AnalysisState.initial(
                new AnalysisRunId("run-semantic"),
                new AnalysisAttemptId("attempt-semantic"),
                AnalysisBudget.of(20, 10));
        AnalysisState scoped = committer.apply(initial, new AnalysisEvent.ScopeResolved(
                initial.runId(), initial.attemptId(), initial.stateRevision(), RepositoryScope.of(List.of(
                        new RepositorySelection(
                                ORDER_REPOSITORY, "initial scope", true, RepositoryDiscoverySource.USER)))));
        AnalysisState pinned = committer.apply(scoped, new AnalysisEvent.RevisionPinned(
                scoped.runId(), scoped.attemptId(), scoped.stateRevision(), ORDER_REPOSITORY, ORDER_REVISION));
        AnalysisState registered = committer.apply(pinned, new AnalysisEvent.NeedRegistered(
                pinned.runId(), pinned.attemptId(), pinned.stateRevision(), mainNeed()));
        return new Fixture(new SemanticResultHandler(committer), adapter, registered, adapter.events().size());
    }

    private AnalysisState register(Fixture fixture, AnalysisState state, InformationNeed need) {
        return new TransitionCommitter(new DefaultStateReducer(), fixture.adapter()).apply(state,
                new AnalysisEvent.NeedRegistered(
                        state.runId(), state.attemptId(), state.stateRevision(), need));
    }

    private List<AnalysisEvent> newEvents(Fixture fixture) {
        return fixture.adapter().events().subList(fixture.initialEventCount(), fixture.adapter().events().size());
    }

    private static SemanticQueryResult success(
            List<EvidenceRef> evidence,
            List<RepositoryDiscovery> discoveries) {
        return new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(ORDER_REVISION),
                evidence,
                discoveries,
                Optional.empty());
    }

    private static SemanticQueryResult partial(List<EvidenceRef> evidence) {
        return new SemanticQueryResult(
                SemanticResultStatus.PARTIAL,
                Optional.of(ORDER_REVISION),
                evidence,
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.PARTIAL_RESULT, "partial result", false)));
    }

    private static SemanticQueryResult failure(
            SemanticResultStatus status,
            SemanticFailureCode code,
            boolean retryable) {
        return new SemanticQueryResult(
                status,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(code, "semantic failure", retryable)));
    }

    private static EvidenceRef evidence(RepositoryRevision revision, String digest) {
        return new EvidenceRef("semantic", ORDER_REPOSITORY, revision, target(), 1.0, List.of(), new ArtifactRef(digest));
    }

    private static RepositoryDiscovery discovery(EvidenceRef evidence) {
        return new RepositoryDiscovery(NOTIFICATION_REPOSITORY, "resolved consumer", evidence);
    }

    private static InformationNeed mainNeed() {
        return new InformationNeed(
                new InformationNeedId("need-main"),
                InformationNeedType.ENTRY_POINT,
                "find order entry point",
                true,
                List.of(ORDER_REPOSITORY),
                List.of(target()));
    }

    private static InformationNeed discoveredNeed() {
        return new InformationNeed(
                new InformationNeedId("need-discovered"),
                InformationNeedType.ENTRY_POINT,
                "find notification consumer",
                true,
                List.of(NOTIFICATION_REPOSITORY),
                List.of(target()));
    }

    private static SemanticTarget target() {
        return new SemanticTarget(SemanticTargetKind.SYMBOL, "com.example.OrderController#create", Optional.empty());
    }

    private record Fixture(
            SemanticResultHandler handler,
            InMemoryAnalysisTransitionAdapter adapter,
            AnalysisState state,
            int initialEventCount) {
    }

    private static final RepositoryId ORDER_REPOSITORY = new RepositoryId("order-service");
    private static final RepositoryId NOTIFICATION_REPOSITORY = new RepositoryId("notification-service");
    private static final RepositoryRevision ORDER_REVISION = new RepositoryRevision("order-1");
}
