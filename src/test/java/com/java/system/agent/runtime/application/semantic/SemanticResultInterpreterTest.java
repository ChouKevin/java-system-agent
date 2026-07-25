package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.application.goal.ProgressFingerprint;
import com.java.system.agent.runtime.application.state.AnalysisEvent;
import com.java.system.agent.runtime.application.state.AnalysisTransitionCommitException;
import com.java.system.agent.runtime.application.state.DefaultStateReducer;
import com.java.system.agent.runtime.application.state.StateTransition;
import com.java.system.agent.runtime.application.state.TransitionCommitter;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.need.InformationNeedType;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.port.out.AnalysisTransitionPort;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import com.java.system.agent.runtime.port.out.SemanticFailure;
import com.java.system.agent.runtime.port.out.SemanticFailureCode;
import com.java.system.agent.runtime.port.out.SemanticQuery;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;
import com.java.system.agent.runtime.port.out.SemanticResultStatus;
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

class SemanticResultInterpreterTest {

    @Test
    void successAcceptsEvidenceBeforeExpandingScopeAndThenResolvesTheNeed() {
        Fixture fixture = fixture();
        InformationNeed discoveredNeed = discoveredNeed();
        AttemptState state = register(fixture, fixture.state(), discoveredNeed);
        EvidenceRef evidence = evidence(ORDER_REVISION, "sha256:success");
        RepositoryDiscovery discovery = discovery(evidence);

        SemanticStepResult result = handle(
                fixture.handler(),
                state,
                mainNeed(),
                success(List.of(evidence), List.of(discovery)),
                false);

        assertThat(result.disposition()).isEqualTo(SemanticStepOutcome.PROGRESSED);
        assertThat(result.failure()).isEmpty();
        assertThat(result.newDiscoveries()).containsExactly(discovery);
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

        SemanticStepResult first = handle(
                fixture.handler(),
                fixture.state(), mainNeed(), partial, false);
        ProgressFingerprint firstFingerprint = ProgressFingerprint.from(first.state());
        int committedAfterFirst = fixture.adapter().events().size();
        SemanticStepResult repeated = handle(
                fixture.handler(),
                first.state(), mainNeed(), partial, false);

        assertThat(first.disposition()).isEqualTo(SemanticStepOutcome.PARTIAL);
        assertThat(first.failure()).contains(partial.failure().orElseThrow());
        assertThat(first.state().pendingNeeds()).containsKey(mainNeed().id());
        assertThat(first.state().warnings()).extracting(warning -> warning.code())
                .containsExactly("SEMANTIC_PARTIAL_RESULT");
        assertThat(repeated.disposition()).isEqualTo(SemanticStepOutcome.PARTIAL);
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

        SemanticStepResult result = handle(
                fixture.handler(),
                fixture.state(), mainNeed(), mismatch, false);

        assertThat(result.disposition()).isEqualTo(SemanticStepOutcome.STALE);
        assertThat(result.state()).isSameAs(fixture.state());
        assertThat(newEvents(fixture)).isEmpty();
    }

    @Test
    void revisionMismatchReportingThePinnedRevisionFailsAsProtocolError() {
        Fixture fixture = fixture();
        SemanticQueryResult incoherentMismatch = new SemanticQueryResult(
                SemanticResultStatus.REVISION_MISMATCH,
                Optional.of(ORDER_REVISION),
                List.of(),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.REVISION_MISMATCH,
                        "semantic service reported an unchanged revision",
                        true)));

        SemanticStepResult handled = fixture.handler().handle(
                fixture.state(),
                query(mainNeed(), ORDER_REPOSITORY, ORDER_REVISION),
                incoherentMismatch,
                false);

        assertThat(handled.disposition()).isEqualTo(SemanticStepOutcome.FAILED);
        assertThat(handled.failure()).get()
                .extracting(SemanticFailure::code)
                .isEqualTo(SemanticFailureCode.PROTOCOL_ERROR);
        assertThat(handled.state().evidenceBindings()).isEmpty();
        assertThat(handled.state().warnings()).extracting(warning -> warning.code())
                .containsExactly("SEMANTIC_PROTOCOL_ERROR");
        assertThat(handled.newDiscoveries()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("transientResults")
    void transientResultsAreRetryableOnlyWhenCoordinatorAllowsRetry(
            SemanticQueryResult result,
            String warningCode) {
        Fixture retryFixture = fixture();
        Fixture blockedFixture = fixture();

        SemanticStepResult retry = handle(
                retryFixture.handler(),
                retryFixture.state(), mainNeed(), result, true);
        SemanticStepResult blocked = handle(
                blockedFixture.handler(),
                blockedFixture.state(), mainNeed(), result, false);

        assertThat(retry.disposition()).isEqualTo(SemanticStepOutcome.RETRYABLE);
        assertThat(retry.state()).isSameAs(retryFixture.state());
        assertThat(newEvents(retryFixture)).isEmpty();
        assertThat(blocked.disposition()).isEqualTo(SemanticStepOutcome.BLOCKED);
        assertThat(blocked.state().warnings()).extracting(warning -> warning.code())
                .containsExactly(warningCode);
    }

    @ParameterizedTest
    @MethodSource("blockedResults")
    void nonTransientBlockedResultsRecordTheirStableWarningCode(
            SemanticQueryResult result,
            String warningCode) {
        Fixture fixture = fixture();

        SemanticStepResult handled = handle(
                fixture.handler(),
                fixture.state(), mainNeed(), result, true);

        assertThat(handled.disposition()).isEqualTo(SemanticStepOutcome.BLOCKED);
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

        SemanticStepResult handled = handle(
                fixture.handler(),
                fixture.state(), mainNeed(), result, false);

        assertThat(handled.disposition()).isEqualTo(SemanticStepOutcome.FAILED);
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

        SemanticStepResult result = handle(
                fixture.handler(),
                fixture.state(),
                mainNeed(),
                new SemanticQueryResult(
                        SemanticResultStatus.SUCCESS,
                        Optional.of(mismatchedRevision),
                        List.of(mismatched),
                        List.of(),
                        Optional.empty()),
                false);

        assertThat(result.disposition()).isEqualTo(SemanticStepOutcome.STALE);
        assertThat(result.state()).isSameAs(fixture.state());
        assertThat(newEvents(fixture)).isEmpty();
    }

    @Test
    void evidenceFromPinnedRepositoryOutsideNeedCandidatesFailsAsProtocolError() {
        Fixture fixture = fixture();
        EvidenceRef sourceEvidence = evidence(ORDER_REVISION, "sha256:protocol-scope");
        TransitionCommitter setupCommitter = new TransitionCommitter(
                new DefaultStateReducer(), fixture.adapter());
        AttemptState withEvidence = setupCommitter.apply(fixture.state(), new AnalysisEvent.EvidenceAccepted(
                fixture.state().runId(),
                fixture.state().attemptId(),
                fixture.state().stateRevision(),
                mainNeed().id(),
                sourceEvidence));
        AttemptState withExpandedScope = setupCommitter.apply(withEvidence, new AnalysisEvent.ScopeExpanded(
                withEvidence.runId(),
                withEvidence.attemptId(),
                withEvidence.stateRevision(),
                discovery(sourceEvidence),
                false));
        AttemptState state = setupCommitter.apply(withExpandedScope, new AnalysisEvent.RevisionPinned(
                withExpandedScope.runId(),
                withExpandedScope.attemptId(),
                withExpandedScope.stateRevision(),
                NOTIFICATION_REPOSITORY,
                NOTIFICATION_REVISION));
        int evidenceCount = state.evidenceBindings().size();

        SemanticStepResult handled = handle(
                fixture.handler(),
                state,
                mainNeed(),
                successAt(
                        NOTIFICATION_REVISION,
                        List.of(evidence(
                                NOTIFICATION_REPOSITORY,
                                NOTIFICATION_REVISION,
                                "sha256:invalid-candidate"))),
                false);

        assertThat(handled.disposition()).isEqualTo(SemanticStepOutcome.FAILED);
        assertThat(handled.failure()).get()
                .extracting(SemanticFailure::code)
                .isEqualTo(SemanticFailureCode.PROTOCOL_ERROR);
        assertThat(handled.state().evidenceBindings()).hasSize(evidenceCount);
        assertThat(handled.state().repositoryScope()).isEqualTo(state.repositoryScope());
        assertThat(handled.state().warnings()).extracting(warning -> warning.code())
                .containsExactly("SEMANTIC_PROTOCOL_ERROR");
        assertThat(handled.newDiscoveries()).isEmpty();
    }

    @Test
    void evidenceForCandidateOutsideCurrentScopeFailsAsProtocolError() {
        Fixture fixture = fixture();
        InformationNeed need = dualRepositoryNeed();
        AttemptState state = register(fixture, fixture.state(), need);

        SemanticStepResult handled = handle(
                fixture.handler(),
                state,
                need,
                successAt(
                        NOTIFICATION_REVISION,
                        List.of(evidence(
                                NOTIFICATION_REPOSITORY,
                                NOTIFICATION_REVISION,
                                "sha256:outside-scope"))),
                false);

        assertThat(handled.disposition()).isEqualTo(SemanticStepOutcome.FAILED);
        assertThat(handled.failure()).get()
                .extracting(SemanticFailure::code)
                .isEqualTo(SemanticFailureCode.PROTOCOL_ERROR);
        assertThat(handled.state().evidenceBindings()).isEqualTo(state.evidenceBindings());
        assertThat(handled.state().repositoryScope()).isEqualTo(state.repositoryScope());
        assertThat(handled.state().warnings()).extracting(warning -> warning.code())
                .containsExactly("SEMANTIC_PROTOCOL_ERROR");
        assertThat(handled.newDiscoveries()).isEmpty();
    }

    @Test
    void partialResultPrevalidationRejectsLaterProtocolEvidenceBeforeAcceptance() {
        Fixture fixture = fixture();
        EvidenceRef scopeEvidence = evidence(ORDER_REVISION, "sha256:scope");
        TransitionCommitter setupCommitter = new TransitionCommitter(
                new DefaultStateReducer(), fixture.adapter());
        AttemptState withEvidence = setupCommitter.apply(fixture.state(), new AnalysisEvent.EvidenceAccepted(
                fixture.state().runId(),
                fixture.state().attemptId(),
                fixture.state().stateRevision(),
                mainNeed().id(),
                scopeEvidence));
        AttemptState withExpandedScope = setupCommitter.apply(withEvidence, new AnalysisEvent.ScopeExpanded(
                withEvidence.runId(),
                withEvidence.attemptId(),
                withEvidence.stateRevision(),
                discovery(scopeEvidence),
                false));
        AttemptState withPinnedScope = setupCommitter.apply(withExpandedScope, new AnalysisEvent.RevisionPinned(
                withExpandedScope.runId(),
                withExpandedScope.attemptId(),
                withExpandedScope.stateRevision(),
                NOTIFICATION_REPOSITORY,
                NOTIFICATION_REVISION));
        InformationNeed dualRepositoryNeed = dualRepositoryNeed();
        AttemptState state = register(fixture, withPinnedScope, dualRepositoryNeed);
        int setupEventCount = fixture.adapter().events().size();
        long setupCommitCount = fixture.adapter().commitCount();
        RepositoryRevision mismatchedRevision = new RepositoryRevision("order-other");
        EvidenceRef staleEvidence = evidence(mismatchedRevision, "sha256:prevalidation-order");
        EvidenceRef invalidEvidence = new EvidenceRef(
                "semantic",
                NOTIFICATION_REPOSITORY,
                mismatchedRevision,
                target(),
                1.0,
                List.of(),
                new ArtifactRef("sha256:prevalidation-notification"));
        SemanticQueryResult result = new SemanticQueryResult(
                SemanticResultStatus.PARTIAL,
                Optional.of(mismatchedRevision),
                List.of(staleEvidence, invalidEvidence),
                List.of(),
                Optional.of(new SemanticFailure(
                        SemanticFailureCode.PARTIAL_RESULT,
                        "partial result",
                        false)));

        SemanticStepResult handled = handle(
                fixture.handler(),
                state, dualRepositoryNeed, result, false);

        assertThat(handled.disposition()).isEqualTo(SemanticStepOutcome.FAILED);
        assertThat(handled.failure()).get()
                .extracting(SemanticFailure::code)
                .isEqualTo(SemanticFailureCode.PROTOCOL_ERROR);
        assertThat(fixture.adapter().events()).hasSize(setupEventCount + 1);
        assertThat(fixture.adapter().events().getLast())
                .isInstanceOf(AnalysisEvent.WarningRecorded.class);
        assertThat(fixture.adapter().commitCount()).isEqualTo(setupCommitCount + 1);
        assertThat(handled.state().evidenceBindings()).isEqualTo(state.evidenceBindings());
        assertThat(handled.state().warnings()).extracting(warning -> warning.code())
                .containsExactly("SEMANTIC_PROTOCOL_ERROR");
        assertThat(handled.state().repositoryScope()).isEqualTo(state.repositoryScope());
        assertThat(handled.state().revisionVector()).isEqualTo(state.revisionVector());
    }

    @Test
    void scopeExpansionCommitFailurePropagatesAfterEvidenceAndBeforeNeedResolution() {
        Fixture fixture = fixture();
        EvidenceRef evidence = evidence(ORDER_REVISION, "sha256:scope-failure");
        RepositoryDiscovery discovery = discovery(evidence);
        AnalysisTransitionCommitException expected = new AnalysisTransitionCommitException(
                "scope expansion rejected");
        List<AnalysisEvent> attemptedEvents = new ArrayList<>();
        List<AnalysisEvent> committedEvents = new ArrayList<>();
        AnalysisTransitionPort<StateTransition> transitionPort = transition -> {
            attemptedEvents.add(transition.event());
            if (transition.event() instanceof AnalysisEvent.ScopeExpanded) {
                throw expected;
            }
            committedEvents.add(transition.event());
            return transition.candidateState();
        };
        SemanticResultInterpreter handler = new SemanticResultInterpreter(
                new TransitionCommitter(new DefaultStateReducer(), transitionPort));

        assertThatThrownBy(() -> handle(
                handler,
                fixture.state(), mainNeed(), success(List.of(evidence), List.of(discovery)), false))
                .isSameAs(expected);

        assertThat(attemptedEvents)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("EvidenceAccepted", "ScopeExpanded");
        assertThat(committedEvents)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("EvidenceAccepted");
        assertThat(attemptedEvents).noneMatch(event -> event instanceof AnalysisEvent.NeedResolved);
        assertThat(attemptedEvents).noneMatch(event -> event instanceof AnalysisEvent.WarningRecorded);
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

        assertThatIllegalArgumentException().isThrownBy(() -> handle(
                fixture.handler(),
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
                SemanticStepOutcome.PROGRESSED,
                Optional.empty(),
                discoveries);
        discoveries.clear();

        assertThat(result.newDiscoveries()).hasSize(1);
        assertThatThrownBy(() -> result.newDiscoveries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void protocolWarningCommitFailurePropagatesUnchanged() {
        Fixture fixture = fixture();
        InformationNeed need = dualRepositoryNeed();
        AttemptState state = register(fixture, fixture.state(), need);
        AnalysisTransitionCommitException expected = new AnalysisTransitionCommitException(
                "protocol warning rejected");
        SemanticResultInterpreter handler = new SemanticResultInterpreter(new TransitionCommitter(
                new DefaultStateReducer(), transition -> {
                    throw expected;
                }));

        assertThatThrownBy(() -> handle(
                handler,
                state,
                need,
                successAt(
                        NOTIFICATION_REVISION,
                        List.of(evidence(
                                NOTIFICATION_REPOSITORY,
                                NOTIFICATION_REVISION,
                                "sha256:protocol-warning-failure"))),
                false))
                .isSameAs(expected);
    }

    @Test
    void transitionCommitFailuresPropagateUnchanged() {
        Fixture fixture = fixture();
        AnalysisTransitionCommitException expected = new AnalysisTransitionCommitException("commit rejected");
        TransitionCommitter failingCommitter = new TransitionCommitter(
                new DefaultStateReducer(), transition -> {
                    throw expected;
                });
        SemanticResultInterpreter handler = new SemanticResultInterpreter(failingCommitter);

        assertThatThrownBy(() -> handle(
                handler,
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
        AttemptState initial = AttemptState.initial(
                new AnalysisRunId("run-semantic"),
                new AnalysisAttemptId("attempt-semantic"),
                AttemptBudget.of(20, 10));
        AttemptState scoped = committer.apply(initial, new AnalysisEvent.ScopeResolved(
                initial.runId(), initial.attemptId(), initial.stateRevision(), RepositoryScope.of(List.of(
                        new RepositorySelection(
                                ORDER_REPOSITORY, "initial scope", true, RepositoryDiscoverySource.USER)))));
        AttemptState pinned = committer.apply(scoped, new AnalysisEvent.RevisionPinned(
                scoped.runId(), scoped.attemptId(), scoped.stateRevision(), ORDER_REPOSITORY, ORDER_REVISION));
        AttemptState registered = committer.apply(pinned, new AnalysisEvent.NeedRegistered(
                pinned.runId(), pinned.attemptId(), pinned.stateRevision(), mainNeed()));
        return new Fixture(new SemanticResultInterpreter(committer), adapter, registered, adapter.events().size());
    }

    private AttemptState register(Fixture fixture, AttemptState state, InformationNeed need) {
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
        return successAt(ORDER_REVISION, evidence, discoveries);
    }

    private static SemanticQueryResult successAt(
            RepositoryRevision revision,
            List<EvidenceRef> evidence) {
        return successAt(revision, evidence, List.of());
    }

    private static SemanticQueryResult successAt(
            RepositoryRevision revision,
            List<EvidenceRef> evidence,
            List<RepositoryDiscovery> discoveries) {
        return new SemanticQueryResult(
                SemanticResultStatus.SUCCESS,
                Optional.of(revision),
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
        return evidence(ORDER_REPOSITORY, revision, digest);
    }

    private static EvidenceRef evidence(
            RepositoryId repositoryId,
            RepositoryRevision revision,
            String digest) {
        return new EvidenceRef(
                "semantic",
                repositoryId,
                revision,
                target(),
                1.0,
                List.of(),
                new ArtifactRef(digest));
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

    private static InformationNeed dualRepositoryNeed() {
        return new InformationNeed(
                new InformationNeedId("need-dual-repository"),
                InformationNeedType.ENTRY_POINT,
                "find an entry point in either repository",
                true,
                List.of(ORDER_REPOSITORY, NOTIFICATION_REPOSITORY),
                List.of(target()));
    }

    private static SemanticTarget target() {
        return new SemanticTarget(SemanticTargetKind.SYMBOL, "com.example.OrderController#create", Optional.empty());
    }

    private static SemanticStepResult handle(
            SemanticResultInterpreter handler,
            AttemptState state,
            InformationNeed informationNeed,
            SemanticQueryResult result,
            boolean retryAllowed) {
        return handler.handle(
                state,
                query(informationNeed, ORDER_REPOSITORY, ORDER_REVISION),
                result,
                retryAllowed);
    }

    private static SemanticQuery query(
            InformationNeed informationNeed,
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision) {
        return new SemanticQuery(
                "test-capability",
                informationNeed,
                target(),
                repositoryId,
                expectedRevision);
    }

    private record Fixture(
            SemanticResultInterpreter handler,
            InMemoryAnalysisTransitionAdapter adapter,
            AttemptState state,
            int initialEventCount) {
    }

    private static final RepositoryId ORDER_REPOSITORY = new RepositoryId("order-service");
    private static final RepositoryId NOTIFICATION_REPOSITORY = new RepositoryId("notification-service");
    private static final RepositoryRevision ORDER_REVISION = new RepositoryRevision("order-1");
    private static final RepositoryRevision NOTIFICATION_REVISION = new RepositoryRevision("notification-1");
}
