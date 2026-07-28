package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.adapter.fake.FakeAttemptIdGenerator;
import com.java.system.agent.runtime.adapter.fake.FakeCancellationAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeCapabilityCatalogAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeSessionAdapter;
import com.java.system.agent.runtime.adapter.fake.FakeRepositoryCatalogAdapter;
import com.java.system.agent.runtime.application.state.AgentStateReducer;
import com.java.system.agent.runtime.application.state.AgentTransitionCommitter;
import com.java.system.agent.runtime.application.validation.AgentActionValidator;
import com.java.system.agent.runtime.application.validation.AnswerDocumentValidator;
import com.java.system.agent.runtime.application.validation.AnswerVerdictValidator;
import com.java.system.agent.runtime.domain.action.AnswerAction;
import com.java.system.agent.runtime.domain.action.ClarifyAction;
import com.java.system.agent.runtime.domain.action.QueryAction;
import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.domain.answer.AnswerVerificationMode;
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.candidate.RepositoryCandidate;
import com.java.system.agent.runtime.domain.candidate.RouteCandidate;
import com.java.system.agent.runtime.domain.capability.CapabilityPolicy;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
import com.java.system.agent.runtime.domain.observation.ObservationSource;
import com.java.system.agent.runtime.domain.run.AgentEvent;
import com.java.system.agent.runtime.domain.run.AgentBootstrap;
import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentTransition;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.out.AgentActionPort;
import com.java.system.agent.runtime.port.out.AgentActionProposal;
import com.java.system.agent.runtime.port.out.AgentPromptContext;
import com.java.system.agent.runtime.port.out.CapabilityInvocation;
import com.java.system.agent.runtime.port.out.CapabilityExecutionPort;
import com.java.system.agent.runtime.port.out.CapabilityExecutionContractException;
import com.java.system.agent.runtime.port.out.CapabilityExecutionResult;
import com.java.system.agent.runtime.port.out.CapabilityExecutionFailure;
import com.java.system.agent.runtime.port.out.CapabilityExecutionFailureCode;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
import com.java.system.agent.runtime.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.runtime.port.out.AgentTransitionPort;
import com.java.system.agent.runtime.port.out.AgentTransitionConflictException;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import com.java.system.agent.runtime.port.out.AnswerVerificationResult.LlmVerdict;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import com.java.system.agent.runtime.port.out.RepositoryCatalogPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
import com.java.system.agent.runtime.port.out.RepositoryRevisionFailure;
import com.java.system.agent.runtime.port.out.RepositoryRevisionFailureCode;
import com.java.system.agent.runtime.port.out.RepositoryRevisionContractException;
import com.java.system.agent.runtime.port.in.AnswerExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ValidatedAgentLoop query order、拒絕與 final reserve 行為測試
 */
class ValidatedAgentLoopTest {

    @Test
    void cancellationBeforeActionConcludesWithoutRequestingAnAction() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        FakeCancellationAdapter cancellation = new FakeCancellationAdapter().requestCancellationAfter(runId, 0);
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> {
                    actionCalls.incrementAndGet();
                    throw new AssertionError("cancelled loop must not request an action");
                },
                query -> {
                    throw new AssertionError("cancelled loop must not execute semantic query");
                },
                transitions,
                cancellation);

        AgentLoopResult result = loop.execute(request());

        assertCancelled(result, transitions);
        assertThat(actionCalls).hasValue(0);
    }

    @Test
    void cancellationAfterNextQueryActionPreventsAcceptanceAndSemanticExecution() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        FakeCancellationAdapter cancellation = new FakeCancellationAdapter().requestCancellationAfter(runId, 1);
        AtomicInteger capabilityCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Trace repository",
                        Map.of(),
                        "Repository may contain the answer")),
                query -> {
                    capabilityCalls.incrementAndGet();
                    throw new AssertionError("cancelled query must not execute semantic query");
                },
                transitions,
                cancellation);

        AgentLoopResult result = loop.execute(request());

        assertCancelled(result, transitions);
        assertThat(capabilityCalls).hasValue(0);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ActionAccepted.class::isInstance)
                .isEmpty();
        assertThat(transitions.events())
                .filteredOn(AgentEvent.QueryBudgetConsumed.class::isInstance)
                .isEmpty();
    }

    @Test
    void cancellationAfterAnswerProposalPreventsVerification() {
        AnalysisRunId runId = new AnalysisRunId("run-1");
        FakeCancellationAdapter cancellation = new FakeCancellationAdapter().requestCancellationAfter(runId, 1);
        AtomicInteger verificationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> new AgentActionProposal.Proposed(new AnswerAction(document("Cancelled answer"))),
                query -> {
                    throw new AssertionError("answer action must not execute semantic query");
                },
                transitions,
                cancellation,
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    throw new AssertionError("cancelled answer must not be verified");
                });

        AgentLoopResult result = loop.execute(request());

        assertCancelled(result, transitions);
        assertThat(verificationCalls).hasValue(0);
    }

    @Test
    void cancellationDuringNextActionPreventsTerminalAcceptanceAndAllDownstreamWork() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger capabilityCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> {
                    cancelled.set(true);
                    return new AgentActionProposal.Proposed(
                            new ClarifyAction("Which repository?", List.of(), "Need scope"));
                },
                query -> {
                    capabilityCalls.incrementAndGet();
                    throw new AssertionError("cancelled loop must not execute semantic query");
                },
                transitions,
                (AnalysisCancellationPort) (runId -> cancelled.get()),
                (mode, context) -> {
                    verificationCalls.incrementAndGet();
                    throw new AssertionError("cancelled loop must not verify an answer");
                });

        AgentLoopResult result = loop.execute(request());

        assertCancelled(result, transitions);
        assertThat(capabilityCalls).hasValue(0);
        assertThat(verificationCalls).hasValue(0);
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.AnswerAccepted
                        || event instanceof AgentEvent.ClarificationAccepted)
                .isEmpty();
    }

    @Test
    void concurrentEmptyReadsAllowOnlyOneAtomicBootstrapAndOneActionFlow() throws Exception {
        RacingTransitionPort transitions = new RacingTransitionPort();
        AtomicInteger actionCalls = new AtomicInteger();
        AgentActionPort actionPort = context -> {
            actionCalls.incrementAndGet();
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which repository?", List.of(), "Need scope"));
        };
        ValidatedAgentLoop firstCaller = loop(actionPort, query -> {
            throw new AssertionError("clarification must not execute a semantic query");
        }, transitions);
        ValidatedAgentLoop secondCaller = loop(actionPort, query -> {
            throw new AssertionError("clarification must not execute a semantic query");
        }, transitions);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CallerResult> first = executor.submit(() -> executeCaller(firstCaller));
            Future<CallerResult> second = executor.submit(() -> executeCaller(secondCaller));

            List<CallerResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results)
                    .filteredOn(result -> result.result().isPresent())
                    .hasSizeGreaterThanOrEqualTo(1)
                    .allSatisfy(result -> assertThat(result.result().orElseThrow().outcome())
                            .isEqualTo(RunOutcome.INCONCLUSIVE));
            assertThat(results)
                    .filteredOn(result -> result.inProgress().isPresent())
                    .hasSizeLessThanOrEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(actionCalls).hasValue(1);
        assertThat(transitions.bootstrapCount()).isEqualTo(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.RunStarted.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events().stream().limit(3).map(event -> event.getClass().getSimpleName()).toList())
                .containsExactly("RunStarted", "AttemptStarted", "ContextIssued");
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AttemptStarted.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ContextIssued.class::isInstance)
                .hasSize(1);
    }

    @Test
    void propagatesInitializationFailureWithoutPersistingAnyBootstrapEventOrCallingTheAgent() {
        AtomicInteger actionCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = new ValidatedAgentLoop(
                context -> {
                    actionCalls.incrementAndGet();
                    throw new AssertionError("initialization failure must not request an action");
                },
                query -> {
                    throw new AssertionError("initialization failure must not execute a semantic query");
                },
                (mode, context) -> {
                    throw new AssertionError("initialization failure must not verify an answer");
                },
                AnswerVerificationMode.LLM,
                new FakeSessionAdapter(),
                new RepositoryCatalogPort() {
                    @Override
                    public List<RepositoryDescriptor> availableRepositories() {
                        throw new IllegalStateException("repository catalog unavailable");
                    }
                },
                new FakeCapabilityCatalogAdapter(),
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("unused")),
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitions),
                new ContextIssuer());

        assertThatThrownBy(() -> loop.execute(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("repository catalog unavailable");
        assertThat(actionCalls).hasValue(0);
        assertThat(transitions.events()).isEmpty();
    }

    @Test
    void retriesAPostBootstrapProviderFailureWithANewReducerDrivenAttempt() {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop failingLoop = loop(
                context -> {
                    throw new IllegalStateException("action provider unavailable");
                },
                query -> {
                    throw new AssertionError("provider failure must occur before semantic execution");
                },
                transitions);

        assertThatThrownBy(() -> failingLoop.execute(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("action provider unavailable");
        assertThatThrownBy(() -> failingLoop.execute(request()))
                .isInstanceOf(AgentRunInProgressException.class);

        AtomicInteger retryActionCalls = new AtomicInteger();
        ValidatedAgentLoop retryLoop = loop(
                context -> {
                    retryActionCalls.incrementAndGet();
                    assertThat(context.attemptId()).isEqualTo(new AnalysisAttemptId("attempt-2"));
                    return new AgentActionProposal.Proposed(
                            new ClarifyAction("Which repository?", List.of(), "Need scope"));
                },
                query -> {
                    throw new AssertionError("clarification must not execute a semantic query");
                },
                transitions,
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("unused")),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-2")));
        AgentLoopRequest retryRequest = new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0, 1, 0),
                AnswerExecutionMode.RETRY,
                2);

        AgentLoopResult result = retryLoop.execute(retryRequest);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(retryActionCalls).hasValue(1);
        assertThat(transitions.events())
                .extracting(event -> event.getClass().getSimpleName())
                .containsSubsequence("AttemptInvalidated", "AttemptStarted", "ContextIssued");
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AttemptInvalidated.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEvent.AttemptInvalidated) event).consumeRevisionRestart())
                        .isFalse());
    }

    @Test
    void concludesFailedWhenRevisionLookupViolatesItsContract() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger capabilityCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> {
                    actionCalls.incrementAndGet();
                    return query(context);
                },
                invocation -> {
                    capabilityCalls.incrementAndGet();
                    throw new AssertionError("revision contract failure must stop before capability execution");
                },
                transitions,
                repositoryId -> {
                    throw new RepositoryRevisionContractException("revision response is malformed");
                },
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(actionCalls).hasValue(1);
        assertThat(capabilityCalls).hasValue(0);
        assertThat(transitions.events()).filteredOn(AgentEvent.RunConcluded.class::isInstance).hasSize(1);
    }

    @Test
    void concludesFailedWhenCapabilityExecutionViolatesItsContract() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger capabilityCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> {
                    actionCalls.incrementAndGet();
                    return query(context);
                },
                invocation -> {
                    capabilityCalls.incrementAndGet();
                    throw new CapabilityExecutionContractException("capability response is malformed");
                },
                transitions);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(actionCalls).hasValue(1);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(transitions.events()).filteredOn(AgentEvent.ObservationRecorded.class::isInstance).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.RunConcluded.class::isInstance).hasSize(1);
    }

    @Test
    void concludesFailedWhenCapabilityResultCannotBeIssuedIntoRuntimeContext() {
        AtomicInteger actionCalls = new AtomicInteger();
        AtomicInteger capabilityCalls = new AtomicInteger();
        EvidenceRef duplicate = evidence("revision-repo-1");
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> {
                    actionCalls.incrementAndGet();
                    return query(context);
                },
                invocation -> {
                    capabilityCalls.incrementAndGet();
                    return new CapabilityExecutionResult.Succeeded(List.of(), List.of(duplicate, duplicate), List.of());
                },
                transitions);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(actionCalls).hasValue(1);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(transitions.events()).filteredOn(AgentEvent.ObservationRecorded.class::isInstance).isEmpty();
        assertThat(transitions.events()).filteredOn(AgentEvent.RunConcluded.class::isInstance).hasSize(1);
    }

    @Test
    void concludesWhenSelectedRepositoryQueryReturnsAResultForAnotherRepository() {
        AtomicInteger capabilityCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                this::query,
                invocation -> {
                    capabilityCalls.incrementAndGet();
                    return new CapabilityExecutionResult.Succeeded(
                            List.of(new RouteCandidate(
                                    new RepositoryId("repo-2"),
                                    new RepositoryRevision("revision-repo-2"),
                                    "GET /orders",
                                    "Orders route")),
                            List.of(),
                            List.of());
                },
                transitions);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ContextIssued.class::isInstance)
                .hasSize(2);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow()
                .currentAttempt().issuedCandidates().values())
                .noneMatch(issued -> issued.candidate() instanceof RouteCandidate);
    }

    @Test
    void recordsConflictWithoutASecondRevisionLookupWhenSelectedResultRevisionDiffersFromPinnedRevision() {
        AtomicInteger actionNumber = new AtomicInteger();
        AtomicInteger revisionCalls = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        AgentActionPort actionPort = context -> {
            prompts.add(context);
            if (actionNumber.getAndIncrement() == 0) {
                return query(context);
            }
            assertThat(context.issuedEvidence()).isEmpty();
            assertThat(context.observations().values())
                    .extracting(observation -> observation.code() + ":" + observation.source())
                    .containsExactly(ObservationCode.CONFLICTING_EVIDENCE + ":" + ObservationSource.RUNTIME);
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Should I query the repository again?", List.of(), "Fresh evidence is needed"));
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                actionPort,
                invocation -> new CapabilityExecutionResult.Succeeded(
                        List.of(),
                        List.of(evidence("different-revision")),
                        List.of()),
                transitions,
                repositoryId -> {
                    revisionCalls.incrementAndGet();
                    return RepositoryRevisionResult.ready(new RepositoryRevision("revision-" + repositoryId.value()));
                },
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(revisionCalls).hasValue(1);
        assertThat(prompts).hasSize(2);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ContextIssued.class::isInstance)
                .hasSize(2);
    }

    @Test
    void concludesWhenOneCapabilityResultDeclaresConflictingRevisionsForTheSameRepository() {
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                this::query,
                invocation -> new CapabilityExecutionResult.Succeeded(
                        List.of(new RouteCandidate(
                                new RepositoryId("repo-1"),
                                new RepositoryRevision("revision-repo-1"),
                                "GET /orders",
                                "Orders route")),
                        List.of(evidence("different-revision")),
                        List.of()),
                transitions);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow()
                .currentAttempt().issuedEvidence())
                .isEmpty();
        assertThat(transitions.events())
                .filteredOn(AgentEvent.RunConcluded.class::isInstance)
                .hasSize(1);
    }

    @Test
    void pinsAndRebindsUnscopedRevisionBearingCandidateBeforeSelectingItInTheNextQuery() {
        AtomicInteger actionNumber = new AtomicInteger();
        AtomicInteger revisionCalls = new AtomicInteger();
        AtomicInteger capabilityCalls = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        AgentActionPort actionPort = context -> {
            prompts.add(context);
            if (actionNumber.getAndIncrement() == 0) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(),
                        "Discover routes",
                        Map.of(),
                        "No repository has been selected"));
            }
            CandidateHandle routeHandle = context.issuedCandidates().entrySet().stream()
                    .filter(entry -> entry.getValue().candidate() instanceof RouteCandidate)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();
            RouteCandidate route = (RouteCandidate) context.issuedCandidates().get(routeHandle).candidate();
            assertThat(route.analyzedRevision()).isEqualTo(new RepositoryRevision("revision-repo-1"));
            assertThat(context.issuedCandidates().get(routeHandle)
                    .handle()
                    .binding()
                    .revisionVector()
                    .matches(route.repositoryId(), route.analyzedRevision())).isTrue();
            assertThat(context.latestRejection()).isEmpty();
            if (actionNumber.get() == 2) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(routeHandle),
                        "Trace discovered route",
                        Map.of(),
                        "The discovered route is now revision-pinned"));
            }
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which route should I investigate?", List.of(), "Route scope is now available"));
        };
        CapabilityPolicy routeDiscovery = new CapabilityPolicy(
                "discover-routes",
                "v1",
                Set.of(CandidateKind.REPOSITORY, CandidateKind.ROUTE),
                0,
                10);
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loopWithCapability(
                actionPort,
                invocation -> {
                    int capabilityCall = capabilityCalls.getAndIncrement();
                    if (capabilityCall == 0) {
                        return new CapabilityExecutionResult.Succeeded(
                                List.of(new RouteCandidate(
                                        new RepositoryId("repo-1"),
                                        new RepositoryRevision("revision-repo-1"),
                                        "GET /orders",
                                        "Orders route")),
                                List.of(),
                                List.of());
                    }
                    assertThat(invocation.candidates()).singleElement().satisfies(candidate -> {
                        assertThat(candidate.candidate()).isInstanceOf(RouteCandidate.class);
                        assertThat(candidate.handle().binding().revisionVector().matches(
                                new RepositoryId("repo-1"), new RepositoryRevision("revision-repo-1"))).isTrue();
                    });
                    assertThat(invocation.expectedRevisions().matches(
                            new RepositoryId("repo-1"), new RepositoryRevision("revision-repo-1"))).isTrue();
                    return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
                },
                transitions,
                repositoryId -> {
                    revisionCalls.incrementAndGet();
                    return RepositoryRevisionResult.ready(new RepositoryRevision("revision-" + repositoryId.value()));
                },
                routeDiscovery);

        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                new AttemptBudget(2, 0, 2, 0, 2, 0, 1, 0, 1, 0));

        AgentLoopResult result = loop.execute(request);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(capabilityCalls).hasValue(2);
        assertThat(revisionCalls).hasValue(2);
        assertThat(prompts).hasSize(3);
        assertThat(result.finalRevisions().matches(new RepositoryId("repo-1"), new RepositoryRevision("revision-repo-1")))
                .isTrue();
    }

    @Test
    void doesNotPersistUnscopedRebindWhenResultRepeatsPreviouslyIssuedCandidate() {
        AtomicInteger revisionCalls = new AtomicInteger();
        CapabilityPolicy routeDiscovery = new CapabilityPolicy(
                "discover-routes",
                "v1",
                Set.of(CandidateKind.REPOSITORY, CandidateKind.ROUTE),
                0,
                10);
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loopWithCapability(
                context -> new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(),
                        "Discover routes",
                        Map.of(),
                        "No repository has been selected")),
                invocation -> new CapabilityExecutionResult.Succeeded(
                        List.of(
                                new RouteCandidate(
                                        new RepositoryId("repo-1"),
                                        new RepositoryRevision("revision-repo-1"),
                                        "GET /orders",
                                        "Orders route"),
                                new RepositoryCandidate(new RepositoryId("repo-1"), "Repository one")),
                        List.of(),
                        List.of()),
                transitions,
                repositoryId -> {
                    revisionCalls.incrementAndGet();
                    return RepositoryRevisionResult.ready(new RepositoryRevision("revision-" + repositoryId.value()));
                },
                routeDiscovery);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(revisionCalls).hasValue(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ContextIssued.class::isInstance)
                .hasSize(1);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow()
                .currentAttempt().revisionVector())
                .isEqualTo(RevisionVector.empty());
    }

    @Test
    void unscopedMultiRepositoryResultPrefersRevisionConflictOverFailureWithoutPersistingResults() {
        AtomicInteger actionNumber = new AtomicInteger();
        List<RepositoryId> revisionLookups = new ArrayList<>();
        CapabilityPolicy routeDiscovery = new CapabilityPolicy(
                "discover-routes",
                "v1",
                Set.of(CandidateKind.REPOSITORY, CandidateKind.ROUTE),
                0,
                10);
        AgentActionPort actionPort = context -> {
            if (actionNumber.getAndIncrement() == 0) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(),
                        "Discover routes",
                        Map.of(),
                        "No repository has been selected"));
            }
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which route should I investigate?", List.of(), "Revision evidence conflicts"));
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loopWithCapability(
                actionPort,
                invocation -> new CapabilityExecutionResult.Succeeded(
                        List.of(
                                new RouteCandidate(
                                        new RepositoryId("repo-1"),
                                        new RepositoryRevision("revision-repo-1"),
                                        "GET /orders",
                                        "Orders route"),
                                new RouteCandidate(
                                        new RepositoryId("repo-2"),
                                        new RepositoryRevision("revision-repo-2"),
                                        "GET /payments",
                                        "Payments route")),
                        List.of(),
                        List.of()),
                transitions,
                repositoryId -> {
                    revisionLookups.add(repositoryId);
                    if (repositoryId.equals(new RepositoryId("repo-1"))) {
                        return RepositoryRevisionResult.ready(new RepositoryRevision("different-revision"));
                    }
                    return RepositoryRevisionResult.failed(new RepositoryRevisionFailure(
                            RepositoryRevisionFailureCode.DEPENDENCY_UNAVAILABLE,
                            "repository revision service is temporarily unavailable",
                            "repository-revision-service"));
                },
                routeDiscovery);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(revisionLookups).containsExactly(new RepositoryId("repo-1"), new RepositoryId("repo-2"));
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ContextIssued.class::isInstance)
                .hasSize(1);
        AgentRunState finalState = transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow();
        assertThat(finalState.currentAttempt().revisionVector()).isEqualTo(RevisionVector.empty());
        assertThat(finalState.currentAttempt().issuedCandidates().values())
                .noneMatch(issued -> issued.candidate() instanceof RouteCandidate);
        assertThat(finalState.currentAttempt().observations().values())
                .extracting(observation -> observation.code() + ":" + observation.source())
                .containsExactly(ObservationCode.CONFLICTING_EVIDENCE + ":" + ObservationSource.RUNTIME);
    }

    @Test
    void recordsExecutionFailureWithoutRejectingTheNextPromptWhenSelectedRevisionIsUnavailable() {
        AtomicInteger actionNumber = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        AgentActionPort actionPort = context -> {
            prompts.add(context);
            if (actionNumber.getAndIncrement() == 0) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Trace repository", Map.of(), "Repository evidence is required"));
            }
            assertThat(context.latestRejection()).isEmpty();
            assertThat(context.observations().values())
                    .extracting(observation -> observation.code() + ":" + observation.source())
                    .containsExactly(ObservationCode.EXECUTION_FAILED + ":" + ObservationSource.RUNTIME);
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which repository should I retry?", List.of(), "Revision is unavailable"));
        };
        CapabilityExecutionPort capabilityExecutionPort = invocation -> {
            throw new AssertionError("unavailable selected revision must prevent capability execution");
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                actionPort,
                capabilityExecutionPort,
                transitions,
                repositoryId -> RepositoryRevisionResult.failed(new RepositoryRevisionFailure(
                            RepositoryRevisionFailureCode.DEPENDENCY_UNAVAILABLE,
                            "repository revision service is temporarily unavailable",
                            "repository-revision-service")),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")));

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(prompts).hasSize(2);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ActionAccepted.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.QueryBudgetConsumed.class::isInstance)
                .hasSize(1);
    }

    @Test
    void recordsExecutionFailureWhenAnUnscopedResultRevisionIsUnavailable() {
        AtomicInteger actionNumber = new AtomicInteger();
        CapabilityPolicy routeDiscovery = new CapabilityPolicy(
                "discover-routes",
                "v1",
                Set.of(CandidateKind.REPOSITORY, CandidateKind.ROUTE),
                0,
                10);
        AgentActionPort actionPort = context -> {
            if (actionNumber.getAndIncrement() == 0) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(),
                        "Discover routes",
                        Map.of(),
                        "No repository has been selected"));
            }
            assertThat(context.observations().values())
                    .extracting(observation -> observation.code() + ":" + observation.source())
                    .containsExactly(ObservationCode.EXECUTION_FAILED + ":" + ObservationSource.RUNTIME);
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which route should I investigate?", List.of(), "Revision is unavailable"));
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loopWithCapability(
                actionPort,
                invocation -> new CapabilityExecutionResult.Succeeded(
                        List.of(new RouteCandidate(
                                new RepositoryId("repo-1"),
                                new RepositoryRevision("revision-repo-1"),
                                "GET /orders",
                                "Orders route")),
                        List.of(),
                        List.of()),
                transitions,
                repositoryId -> RepositoryRevisionResult.failed(new RepositoryRevisionFailure(
                        RepositoryRevisionFailureCode.DEPENDENCY_UNAVAILABLE,
                        "repository revision service is temporarily unavailable",
                        "repository-revision-service")),
                routeDiscovery);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow()
                .currentAttempt().issuedCandidates().values())
                .noneMatch(issued -> issued.candidate() instanceof RouteCandidate);
    }

    @Test
    void prioritizesRevisionDriftOverAnotherSelectedRepositoryBeingUnavailable() {
        AtomicInteger actionNumber = new AtomicInteger();
        AgentActionPort actionPort = context -> {
            int actionIndex = actionNumber.getAndIncrement();
            if (actionIndex < 2) {
                List<CandidateHandle> candidates = new ArrayList<>(context.issuedCandidates().keySet());
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        candidates,
                        "Trace both repositories",
                        Map.of(),
                        "Both repositories may contain the answer"));
            }
            assertThat(context.attemptId()).isEqualTo(new AnalysisAttemptId("attempt-2"));
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which repository should I retry?", List.of(), "Fresh evidence is needed"));
        };
        AtomicInteger capabilityCalls = new AtomicInteger();
        CapabilityExecutionPort capabilityExecutionPort = invocation -> {
            capabilityCalls.incrementAndGet();
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        };
        Map<RepositoryId, AtomicInteger> revisionCalls = Map.of(
                new RepositoryId("repo-1"), new AtomicInteger(),
                new RepositoryId("repo-2"), new AtomicInteger());
        RepositoryRevisionPort revisionPort = repositoryId -> {
            int call = revisionCalls.get(repositoryId).getAndIncrement();
            if (repositoryId.equals(new RepositoryId("repo-1")) && call == 1) {
                return RepositoryRevisionResult.failed(new RepositoryRevisionFailure(
                        RepositoryRevisionFailureCode.DEPENDENCY_UNAVAILABLE,
                        "repository revision service is temporarily unavailable",
                        "repository-revision-service"));
            }
            if (repositoryId.equals(new RepositoryId("repo-2")) && call == 1) {
                return RepositoryRevisionResult.ready(new RepositoryRevision("rev-2"));
            }
            return RepositoryRevisionResult.ready(new RepositoryRevision("rev-1"));
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                actionPort,
                capabilityExecutionPort,
                transitions,
                revisionPort,
                new FakeAttemptIdGenerator().register(
                        new AnalysisAttemptId("attempt-1"),
                        new AnalysisAttemptId("attempt-2")));
        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                new AttemptBudget(4, 0, 3, 0, 2, 0, 1, 1, 1, 0));

        AgentLoopResult result = loop.execute(request);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(revisionCalls.values()).allSatisfy(calls -> assertThat(calls).hasValue(2));
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ActionAccepted.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AttemptInvalidated.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEvent.AttemptInvalidated) event).reason())
                        .contains("selected repository revision changed"));
    }

    @Test
    void retryBeforeBootstrapSeedsThePersistedAgentAttemptSequenceFromTheInboxAttempt() {
        List<Integer> generatedAttemptSequences = new ArrayList<>();
        AnalysisAttemptIdGenerator attemptIdGenerator = (runId, attemptSequence) -> {
            generatedAttemptSequences.add(attemptSequence);
            return new AnalysisAttemptId("attempt-" + attemptSequence);
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> new AgentActionProposal.Proposed(
                        new ClarifyAction("Which repository?", List.of(), "Need scope")),
                query -> {
                    throw new AssertionError("clarification must not execute a semantic query");
                },
                transitions,
                repositoryId -> RepositoryRevisionResult.ready(new RepositoryRevision("unused")),
                attemptIdGenerator);

        AgentLoopResult result = loop.execute(new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0, 1, 0),
                AnswerExecutionMode.RETRY,
                2));

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(generatedAttemptSequences).containsExactly(2);
        assertThat(transitions.findByRunId(new AnalysisRunId("run-1")).orElseThrow().attemptSequence())
                .isEqualTo(2);
    }

    @Test
    void retryAfterRevisionRestartUsesTheNextPersistedAgentAttemptSequence() {
        AtomicInteger actionNumber = new AtomicInteger();
        AgentActionPort actionPort = context -> {
            int currentAction = actionNumber.getAndIncrement();
            if (currentAction < 2) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Trace repository",
                        Map.of(),
                        "Repository may contain the answer"));
            }
            if (currentAction == 2) {
                throw new IllegalStateException("action provider unavailable after revision restart");
            }
            assertThat(context.attemptId()).isEqualTo(new AnalysisAttemptId("attempt-3"));
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which repository?", List.of(), "Need scope"));
        };
        AtomicInteger revisionCalls = new AtomicInteger();
        RepositoryRevisionPort revisionPort = repositoryId -> RepositoryRevisionResult.ready(
                new RepositoryRevision(revisionCalls.getAndIncrement() == 0 ? "rev-1" : "rev-2"));
        List<Integer> generatedAttemptSequences = new ArrayList<>();
        AnalysisAttemptIdGenerator attemptIdGenerator = (runId, attemptSequence) -> {
            generatedAttemptSequences.add(attemptSequence);
            return new AnalysisAttemptId("attempt-" + attemptSequence);
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                actionPort,
                query -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(evidence("rev-1")), List.of()),
                transitions,
                revisionPort,
                attemptIdGenerator);
        AttemptBudget budget = new AttemptBudget(5, 0, 3, 0, 3, 0, 1, 1, 1, 0);

        assertThatThrownBy(() -> loop.execute(new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                budget)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("action provider unavailable after revision restart");

        AgentLoopResult result = loop.execute(new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                budget,
                AnswerExecutionMode.RETRY,
                2));

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(generatedAttemptSequences).containsExactly(1, 2, 3);
    }

    @Test
    void preservesLlmCandidateOrderAndUsesFinalReserveAfterQueryBudget() {
        List<CapabilityInvocation> queries = new ArrayList<>();
        AtomicInteger actionNumber = new AtomicInteger();
        AgentActionPort actionPort = context -> {
            if (actionNumber.getAndIncrement() == 0) {
                List<CandidateHandle> candidates = new ArrayList<>(context.issuedCandidates().keySet());
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(candidates.get(1), candidates.get(0)),
                        "Trace both repositories",
                        Map.of(),
                        "Both repositories may participate"));
            }
            assertThat(context.finalResponseMode()).isTrue();
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Which behavior matters most?", List.of(), "Need a narrower question"));
        };
        CapabilityExecutionPort capabilityExecutionPort = query -> {
            queries.add(query);
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(actionPort, capabilityExecutionPort, transitions);

        AgentLoopResult result = loop.execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().candidates())
                .extracting(candidate -> candidate.candidate().repositoryId().value())
                .containsExactly("repo-2", "repo-1");
        assertThat(transitions.events())
                .filteredOn(event -> event instanceof AgentEvent.ContextIssued
                        || event instanceof AgentEvent.ActionAccepted
                        || event instanceof AgentEvent.QueryBudgetConsumed)
                .extracting(event -> event.getClass().getSimpleName())
                .startsWith("ContextIssued", "ActionAccepted", "QueryBudgetConsumed", "ContextIssued");
        AgentEvent.ActionAccepted accepted = (AgentEvent.ActionAccepted) transitions.events().stream()
                .filter(AgentEvent.ActionAccepted.class::isInstance)
                .findFirst()
                .orElseThrow();
        QueryAction acceptedQuery = (QueryAction) accepted.action();
        assertThat(acceptedQuery.candidates())
                .allSatisfy(candidate -> assertThat(candidate.binding().revisionVector()).isEqualTo(RevisionVector.empty()));
        assertThat(queries.getFirst().candidates())
                .allSatisfy(candidate -> assertThat(candidate.handle().binding().revisionVector()).isNotEqualTo(
                        RevisionVector.empty()));
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ClarificationAccepted.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(
                        ((AgentEvent.ClarificationAccepted) event).finalResponseMode()).isTrue());
    }

    @Test
    void unknownCandidateIsRejectedWithoutCallingSemanticPortAndReachesNextPrompt() {
        AtomicInteger capabilityCalls = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        AtomicInteger actionNumber = new AtomicInteger();
        AgentActionPort actionPort = context -> {
            prompts.add(context);
            if (actionNumber.getAndIncrement() == 0) {
                CandidateHandle issued = context.issuedCandidates().keySet().iterator().next();
                CandidateHandle unknown = new CandidateHandle(
                        "unknown",
                        issued.binding(),
                        issued.kind());
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(unknown),
                        "Unknown target",
                        Map.of(),
                        "Exercise validation"));
            }
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("Please identify the repository", List.of(), "Unknown repository"));
        };
        CapabilityExecutionPort capabilityExecutionPort = query -> {
            capabilityCalls.incrementAndGet();
            return new CapabilityExecutionResult.Succeeded(List.of(), List.of(), List.of());
        };

        AgentLoopResult result = loop(actionPort, capabilityExecutionPort, new RecordingTransitionPort()).execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(capabilityCalls).hasValue(0);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1).latestRejection()).contains("UNKNOWN_CANDIDATE");
    }

    @Test
    void rejectedVerdictPersistsEveryUncertaintyCategoryForTheNextPrompt() {
        List<AgentPromptContext> prompts = new ArrayList<>();
        AtomicInteger actionNumber = new AtomicInteger();
        AgentActionPort actionPort = context -> {
            prompts.add(context);
            if (actionNumber.getAndIncrement() == 0) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Find evidence", Map.of(), "Evidence is required"));
            }
            if (actionNumber.get() == 2) {
                return new AgentActionProposal.Proposed(new AnswerAction(new AnswerDocument(List.of(new AnswerStatement(
                        new StatementId("statement-1"), StatementType.FACT, "Rejected fact",
                        Optional.of(new ClaimId("claim-1")), Set.of(context.issuedEvidence().keySet().iterator().next()),
                        Set.of())))));
            }
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("What part should I investigate?", List.of(), "Need a narrower scope"));
        };
        AnswerVerificationPort verifier = (mode, context) -> new com.java.system.agent.runtime.port.out.AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                AnswerDisposition.REJECTED,
                List.of(new StatementVerdict(new StatementId("statement-1"), StatementVerdictStatus.UNSUPPORTED,
                        "unsupported claim text")),
                List.of("unaddressed part text"),
                List.of("blocking uncertainty text"),
                List.of("explicit rejection text")));
        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"), new SessionId("session-1"), "How does this flow work?",
                new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 0, 1, 0));
        ValidatedAgentLoop verifiedLoop = loop(
                actionPort,
                query -> new CapabilityExecutionResult.Succeeded(List.of(), List.of(evidence("revision-repo-1")), List.of()),
                new RecordingTransitionPort(),
                new FakeCancellationAdapter(),
                verifier);

        AgentLoopResult result = verifiedLoop.execute(request);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(prompts).hasSize(3);
        assertThat(prompts.get(2).observations().values())
                .extracting(observation -> observation.code() + ":" + observation.description())
                .containsExactly(
                        ObservationCode.UNSUPPORTED_CLAIM + ":unsupported claim text",
                        ObservationCode.UNADDRESSED_PART + ":unaddressed part text",
                        ObservationCode.BLOCKING_UNCERTAINTY + ":blocking uncertainty text",
                        ObservationCode.ANSWER_REJECTION_REASON + ":explicit rejection text");
        assertThat(prompts.get(2).latestRejection()).contains(
                "unaddressed part: unaddressed part text; blocking uncertainty: blocking uncertainty text; "
                        + "rejection reason: explicit rejection text");
    }

    @Test
    void discardsStaleContextBeforeFinalResponseWhenRevisionRestartBudgetIsExhausted() {
        AtomicInteger actionNumber = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        AgentActionPort actionPort = context -> {
            prompts.add(context);
            if (actionNumber.getAndIncrement() < 2) {
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Trace repository",
                        Map.of(),
                        "Repository may contain the answer"));
            }
            assertThat(context.finalResponseMode()).isTrue();
            assertThat(context.attemptId()).isEqualTo(new AnalysisAttemptId("attempt-2"));
            assertThat(context.issuedEvidence()).isEmpty();
            assertThat(context.observations().values())
                    .extracting(observation -> observation.code())
                    .containsExactly(ObservationCode.CONFLICTING_EVIDENCE);
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("The repository changed; should I retry?", List.of(), "Fresh evidence is needed"));
        };
        AtomicInteger capabilityCalls = new AtomicInteger();
        CapabilityExecutionPort capabilityExecutionPort = query -> {
            capabilityCalls.incrementAndGet();
            return new CapabilityExecutionResult.Succeeded(
                    List.of(),
                    List.of(evidence("rev-1")),
                    List.of());
        };
        AtomicInteger revisionCalls = new AtomicInteger();
        RepositoryRevisionPort revisionPort = repositoryId -> RepositoryRevisionResult.ready(
                new RepositoryRevision(revisionCalls.getAndIncrement() == 0 ? "rev-1" : "rev-2"));
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                actionPort,
                capabilityExecutionPort,
                transitions,
                revisionPort,
                new FakeAttemptIdGenerator().register(
                        new AnalysisAttemptId("attempt-1"),
                        new AnalysisAttemptId("attempt-2")));
        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                new AttemptBudget(4, 0, 3, 0, 2, 0, 1, 1, 1, 0));

        AgentLoopResult result = loop.execute(request);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(prompts).hasSize(3);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AttemptStarted.class::isInstance)
                .hasSize(2);
    }

    @Test
    void waitsForAuthoritativeRevisionDriftBeforeRestartingAfterCapabilityRevisionConflict() {
        AtomicInteger actionNumber = new AtomicInteger();
        List<AgentPromptContext> prompts = new ArrayList<>();
        AgentActionPort actionPort = context -> {
            prompts.add(context);
            int actionIndex = actionNumber.getAndIncrement();
            if (actionIndex == 0 || actionIndex == 1) {
                if (actionIndex == 1) {
                    assertThat(context.attemptId()).isEqualTo(new AnalysisAttemptId("attempt-1"));
                    assertThat(context.issuedEvidence()).isEmpty();
                    assertThat(context.observations().values())
                            .extracting(observation -> observation.code() + ":" + observation.source())
                            .containsExactly(ObservationCode.EXECUTION_FAILED + ":"
                                    + ObservationSource.CAPABILITY_EXECUTOR);
                }
                return new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Trace repository", Map.of(), "Repository may contain the answer"));
            }
            assertThat(context.attemptId()).isEqualTo(new AnalysisAttemptId("attempt-2"));
            assertThat(context.issuedEvidence()).isEmpty();
            return new AgentActionProposal.Proposed(
                    new ClarifyAction("The repository changed; should I retry?", List.of(), "Fresh evidence is needed"));
        };
        AtomicInteger capabilityCalls = new AtomicInteger();
        CapabilityExecutionPort capabilityExecutionPort = invocation -> {
            capabilityCalls.incrementAndGet();
            return new CapabilityExecutionResult.Failed(new CapabilityExecutionFailure(
                    CapabilityExecutionFailureCode.REVISION_CONFLICT,
                    "repository revision changed while the capability was executing",
                    "capability-executor"));
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                actionPort,
                capabilityExecutionPort,
                transitions,
                new RepositoryRevisionPort() {
                    private final AtomicInteger revisionCalls = new AtomicInteger();

                    @Override
                    public RepositoryRevisionResult currentRevision(RepositoryId repositoryId) {
                        return RepositoryRevisionResult.ready(new RepositoryRevision(
                                revisionCalls.getAndIncrement() == 0 ? "rev-1" : "rev-2"));
                    }
                },
                new FakeAttemptIdGenerator().register(
                        new AnalysisAttemptId("attempt-1"),
                        new AnalysisAttemptId("attempt-2")));
        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 1, 1, 0));

        AgentLoopResult result = loop.execute(request);

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(capabilityCalls).hasValue(1);
        assertThat(prompts).hasSize(3);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.ActionAccepted.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.QueryBudgetConsumed.class::isInstance)
                .hasSize(1);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AttemptInvalidated.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEvent.AttemptInvalidated) event).reason())
                        .contains("selected repository revision changed"));
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AttemptStarted.class::isInstance)
                .hasSize(2);
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AgentTransitionPort transitionPort) {
        return loop(actionPort, capabilityExecutionPort, transitionPort, new FakeCancellationAdapter());
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AgentTransitionPort transitionPort,
            AnalysisCancellationPort cancellationPort) {
        return loop(
                actionPort,
                capabilityExecutionPort,
                transitionPort,
                cancellationPort,
                (mode, context) -> new com.java.system.agent.runtime.port.out.AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                        AnswerDisposition.ACCEPTED_COMPLETE,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())));
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AgentTransitionPort transitionPort,
            AnalysisCancellationPort cancellationPort,
            AnswerVerificationPort verifier) {
        return loop(
                actionPort,
                capabilityExecutionPort,
                transitionPort,
                repositoryId -> RepositoryRevisionResult.ready(
                        new RepositoryRevision("revision-" + repositoryId.value())),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                cancellationPort,
                verifier);
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AgentTransitionPort transitionPort,
            RepositoryRevisionPort revisionPort,
            AnalysisAttemptIdGenerator attemptIdGenerator) {
        return loop(
                actionPort,
                capabilityExecutionPort,
                transitionPort,
                revisionPort,
                attemptIdGenerator,
                new FakeCancellationAdapter(),
                (mode, context) -> new com.java.system.agent.runtime.port.out.AnswerVerificationResult.LlmVerdict(new AnswerVerdict(
                        AnswerDisposition.ACCEPTED_COMPLETE,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())));
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AgentTransitionPort transitionPort,
            RepositoryRevisionPort revisionPort,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            AnalysisCancellationPort cancellationPort,
            AnswerVerificationPort verifier) {
        CapabilityPolicy capability = new CapabilityPolicy(
                "trace",
                "v1",
                Set.of(CandidateKind.REPOSITORY),
                1,
                10);
        FakeRepositoryCatalogAdapter repositories = new FakeRepositoryCatalogAdapter(
                new RepositoryDescriptor(new RepositoryId("repo-1"), "Repository one"),
                new RepositoryDescriptor(new RepositoryId("repo-2"), "Repository two"));
        return new ValidatedAgentLoop(
                actionPort,
                capabilityExecutionPort,
                verifier,
                AnswerVerificationMode.LLM,
                new FakeSessionAdapter(),
                repositories,
                new FakeCapabilityCatalogAdapter(capability),
                revisionPort,
                cancellationPort,
                attemptIdGenerator,
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitionPort),
                new ContextIssuer());
    }

    private ValidatedAgentLoop loopWithCapability(
            AgentActionPort actionPort,
            CapabilityExecutionPort capabilityExecutionPort,
            AgentTransitionPort transitionPort,
            RepositoryRevisionPort revisionPort,
            CapabilityPolicy capability) {
        FakeRepositoryCatalogAdapter repositories = new FakeRepositoryCatalogAdapter(
                new RepositoryDescriptor(new RepositoryId("repo-1"), "Repository one"),
                new RepositoryDescriptor(new RepositoryId("repo-2"), "Repository two"));
        return new ValidatedAgentLoop(
                actionPort,
                capabilityExecutionPort,
                (mode, context) -> new LlmVerdict(
                        new AnswerVerdict(
                                AnswerDisposition.ACCEPTED_COMPLETE,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of())),
                AnswerVerificationMode.LLM,
                new FakeSessionAdapter(),
                repositories,
                new FakeCapabilityCatalogAdapter(capability),
                revisionPort,
                new FakeCancellationAdapter(),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                new AgentActionValidator(),
                new AnswerDocumentValidator(),
                new AnswerVerdictValidator(),
                new AgentTransitionCommitter(new AgentStateReducer(), transitionPort),
                new ContextIssuer());
    }

    private AgentLoopRequest request() {
        return new AgentLoopRequest(
                new AnalysisRunId("run-1"),
                new SessionId("session-1"),
                "How does this flow work?",
                new AttemptBudget(2, 0, 1, 0, 2, 0, 1, 0, 1, 0));
    }

    private EvidenceRef evidence(String revision) {
        return new EvidenceRef(
                "semantic",
                new RepositoryId("repo-1"),
                new RepositoryRevision(revision),
                new SemanticTarget(SemanticTargetKind.SYMBOL, "Orders#create", Optional.empty()),
                "Evidence",
                List.of(),
                new ArtifactRef("digest-" + revision));
    }

    private AgentActionProposal query(AgentPromptContext context) {
        return new AgentActionProposal.Proposed(new QueryAction(
                context.issuedCapabilities().keySet().iterator().next(),
                List.of(context.issuedCandidates().keySet().iterator().next()),
                "Trace repository",
                Map.of(),
                "Repository may contain the answer"));
    }

    private AnswerDocument document(String text) {
        return new AnswerDocument(List.of(new AnswerStatement(
                new StatementId("statement-1"), StatementType.QUESTION, text, Optional.empty(), Set.of(), Set.of())));
    }

    private static void assertCancelled(AgentLoopResult result, RecordingTransitionPort transitions) {
        assertThat(result.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.RunConcluded.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEvent.RunConcluded) event).outcome())
                        .isEqualTo(RunOutcome.CANCELLED));
    }

    private CallerResult executeCaller(ValidatedAgentLoop loop) {
        try {
            return new CallerResult(Optional.of(loop.execute(request())), Optional.empty());
        } catch (AgentRunInProgressException exception) {
            return new CallerResult(Optional.empty(), Optional.of(exception));
        }
    }

    private record CallerResult(
            Optional<AgentLoopResult> result,
            Optional<AgentRunInProgressException> inProgress) {
    }

    private static final class RecordingTransitionPort implements AgentTransitionPort {

        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState existing = states.get(bootstrap.finalTransition().candidateState().runId());
            if (Objects.nonNull(existing)) {
                throw new AgentTransitionConflictException("run already exists");
            }
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            states.put(bootstrap.finalTransition().candidateState().runId(), bootstrap.finalTransition().candidateState());
            return bootstrap.finalTransition().candidateState();
        }

        @Override
        public synchronized AgentRunState commit(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (transition.event() instanceof AgentEvent.RunStarted
                    || transition.event() instanceof AgentEvent.AttemptStarted && transition.event().expectedStateRevision() == 1) {
                throw new IllegalArgumentException("bootstrap events require an atomic bootstrap commit");
            }
            if (Objects.isNull(existing)
                    || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            events.add(transition.event());
            states.put(transition.candidateState().runId(), transition.candidateState());
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
    }

    private static final class RacingTransitionPort implements AgentTransitionPort {

        private final List<AgentEvent> events = new ArrayList<>();
        private final Map<AnalysisRunId, AgentRunState> states = new LinkedHashMap<>();
        private final CyclicBarrier emptyReadBarrier = new CyclicBarrier(2);
        private final AtomicInteger emptyReadParticipants = new AtomicInteger();
        private int bootstrapCount;

        @Override
        public synchronized AgentRunState bootstrap(AgentBootstrap bootstrap) {
            AgentRunState existing = states.get(bootstrap.finalTransition().candidateState().runId());
            if (Objects.nonNull(existing)) {
                throw new AgentTransitionConflictException("run already exists");
            }
            events.add(bootstrap.runStarted().event());
            events.add(bootstrap.attemptStarted().event());
            events.add(bootstrap.contextIssued().event());
            states.put(bootstrap.finalTransition().candidateState().runId(), bootstrap.finalTransition().candidateState());
            bootstrapCount++;
            return bootstrap.finalTransition().candidateState();
        }

        @Override
        public synchronized AgentRunState commit(AgentTransition transition) {
            AgentRunState existing = states.get(transition.candidateState().runId());
            if (Objects.isNull(existing)
                    || existing.stateRevision() != transition.event().expectedStateRevision()) {
                throw new AgentTransitionConflictException("stale transition revision");
            }
            events.add(transition.event());
            states.put(transition.candidateState().runId(), transition.candidateState());
            return transition.candidateState();
        }

        @Override
        public synchronized AgentRunState commitTerminalAcceptance(AgentTransition transition) {
            return commit(transition);
        }

        @Override
        public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
            boolean absent;
            synchronized (this) {
                absent = Objects.isNull(states.get(runId));
            }
            if (absent && emptyReadParticipants.incrementAndGet() <= 2) {
                awaitEmptyReadBarrier();
                return Optional.empty();
            }
            synchronized (this) {
                return Optional.ofNullable(states.get(runId));
            }
        }

        private void awaitEmptyReadBarrier() {
            try {
                emptyReadBarrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("empty read race was interrupted", exception);
            } catch (BrokenBarrierException | TimeoutException exception) {
                throw new IllegalStateException("empty read race did not reach both callers", exception);
            }
        }

        private synchronized int bootstrapCount() {
            return bootstrapCount;
        }

        private synchronized List<AgentEvent> events() {
            return List.copyOf(events);
        }
    }
}
