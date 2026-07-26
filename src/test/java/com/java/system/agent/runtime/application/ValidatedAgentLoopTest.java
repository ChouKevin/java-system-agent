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
import com.java.system.agent.runtime.domain.answer.AnswerDisposition;
import com.java.system.agent.runtime.domain.answer.AnswerDocument;
import com.java.system.agent.runtime.domain.answer.AnswerStatement;
import com.java.system.agent.runtime.domain.answer.ClaimId;
import com.java.system.agent.runtime.domain.answer.StatementId;
import com.java.system.agent.runtime.domain.answer.StatementType;
import com.java.system.agent.runtime.domain.answer.StatementVerdict;
import com.java.system.agent.runtime.domain.answer.StatementVerdictStatus;
import com.java.system.agent.runtime.domain.candidate.CandidateKind;
import com.java.system.agent.runtime.domain.capability.CapabilityDescriptor;
import com.java.system.agent.runtime.domain.capability.CapabilityQuerySchema;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.evidence.ArtifactRef;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.evidence.SemanticTargetKind;
import com.java.system.agent.runtime.domain.handle.CandidateHandle;
import com.java.system.agent.runtime.domain.observation.ObservationCode;
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
import com.java.system.agent.runtime.port.out.AgentSemanticQuery;
import com.java.system.agent.runtime.port.out.AgentSemanticQueryPort;
import com.java.system.agent.runtime.port.out.AgentSemanticQueryResult;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
import com.java.system.agent.runtime.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.runtime.port.out.AgentTransitionPort;
import com.java.system.agent.runtime.port.out.AgentTransitionConflictException;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;
import com.java.system.agent.runtime.port.out.RepositoryCatalogPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;
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
        AtomicInteger semanticCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> new AgentActionProposal.Proposed(new QueryAction(
                        context.issuedCapabilities().keySet().iterator().next(),
                        List.of(context.issuedCandidates().keySet().iterator().next()),
                        "Trace repository",
                        Map.of(),
                        "Repository may contain the answer")),
                query -> {
                    semanticCalls.incrementAndGet();
                    throw new AssertionError("cancelled query must not execute semantic query");
                },
                transitions,
                cancellation);

        AgentLoopResult result = loop.execute(request());

        assertCancelled(result, transitions);
        assertThat(semanticCalls).hasValue(0);
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
                context -> {
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
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger verificationCalls = new AtomicInteger();
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(
                context -> {
                    cancelled.set(true);
                    return new AgentActionProposal.Proposed(
                            new ClarifyAction("Which repository?", List.of(), "Need scope"));
                },
                query -> {
                    semanticCalls.incrementAndGet();
                    throw new AssertionError("cancelled loop must not execute semantic query");
                },
                transitions,
                runId -> cancelled.get(),
                context -> {
                    verificationCalls.incrementAndGet();
                    throw new AssertionError("cancelled loop must not verify an answer");
                });

        AgentLoopResult result = loop.execute(request());

        assertCancelled(result, transitions);
        assertThat(semanticCalls).hasValue(0);
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
                context -> {
                    throw new AssertionError("initialization failure must not verify an answer");
                },
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
                query -> new AgentSemanticQueryResult(List.of(), List.of(evidence("rev-1")), List.of()),
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
        List<AgentSemanticQuery> queries = new ArrayList<>();
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
        AgentSemanticQueryPort semanticPort = query -> {
            queries.add(query);
            return new AgentSemanticQueryResult(List.of(), List.of(), List.of());
        };
        RecordingTransitionPort transitions = new RecordingTransitionPort();
        ValidatedAgentLoop loop = loop(actionPort, semanticPort, transitions);

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
        AtomicInteger semanticCalls = new AtomicInteger();
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
        AgentSemanticQueryPort semanticPort = query -> {
            semanticCalls.incrementAndGet();
            return new AgentSemanticQueryResult(List.of(), List.of(), List.of());
        };

        AgentLoopResult result = loop(actionPort, semanticPort, new RecordingTransitionPort()).execute(request());

        assertThat(result.outcome()).isEqualTo(RunOutcome.INCONCLUSIVE);
        assertThat(semanticCalls).hasValue(0);
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
        AnswerVerificationPort verifier = context -> new AnswerVerdict(
                AnswerDisposition.REJECTED,
                List.of(new StatementVerdict(new StatementId("statement-1"), StatementVerdictStatus.UNSUPPORTED,
                        "unsupported claim text")),
                List.of("unaddressed part text"),
                List.of("blocking uncertainty text"),
                List.of("explicit rejection text"));
        AgentLoopRequest request = new AgentLoopRequest(
                new AnalysisRunId("run-1"), new SessionId("session-1"), "How does this flow work?",
                new AttemptBudget(3, 0, 2, 0, 2, 0, 1, 0, 1, 0));
        ValidatedAgentLoop verifiedLoop = loop(
                actionPort,
                query -> new AgentSemanticQueryResult(List.of(), List.of(evidence("revision-repo-1")), List.of()),
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
        AtomicInteger semanticCalls = new AtomicInteger();
        AgentSemanticQueryPort semanticPort = query -> {
            semanticCalls.incrementAndGet();
            return new AgentSemanticQueryResult(
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
                semanticPort,
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
        assertThat(semanticCalls).hasValue(1);
        assertThat(prompts).hasSize(3);
        assertThat(transitions.events())
                .filteredOn(AgentEvent.AttemptStarted.class::isInstance)
                .hasSize(2);
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            AgentSemanticQueryPort semanticPort,
            AgentTransitionPort transitionPort) {
        return loop(actionPort, semanticPort, transitionPort, new FakeCancellationAdapter());
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            AgentSemanticQueryPort semanticPort,
            AgentTransitionPort transitionPort,
            AnalysisCancellationPort cancellationPort) {
        return loop(
                actionPort,
                semanticPort,
                transitionPort,
                cancellationPort,
                context -> new AnswerVerdict(
                        AnswerDisposition.ACCEPTED_COMPLETE,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            AgentSemanticQueryPort semanticPort,
            AgentTransitionPort transitionPort,
            AnalysisCancellationPort cancellationPort,
            AnswerVerificationPort verifier) {
        return loop(
                actionPort,
                semanticPort,
                transitionPort,
                repositoryId -> RepositoryRevisionResult.ready(
                        new RepositoryRevision("revision-" + repositoryId.value())),
                new FakeAttemptIdGenerator().register(new AnalysisAttemptId("attempt-1")),
                cancellationPort,
                verifier);
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            AgentSemanticQueryPort semanticPort,
            AgentTransitionPort transitionPort,
            RepositoryRevisionPort revisionPort,
            AnalysisAttemptIdGenerator attemptIdGenerator) {
        return loop(
                actionPort,
                semanticPort,
                transitionPort,
                revisionPort,
                attemptIdGenerator,
                new FakeCancellationAdapter(),
                context -> new AnswerVerdict(
                        AnswerDisposition.ACCEPTED_COMPLETE,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
    }

    private ValidatedAgentLoop loop(
            AgentActionPort actionPort,
            AgentSemanticQueryPort semanticPort,
            AgentTransitionPort transitionPort,
            RepositoryRevisionPort revisionPort,
            AnalysisAttemptIdGenerator attemptIdGenerator,
            AnalysisCancellationPort cancellationPort,
            AnswerVerificationPort verifier) {
        CapabilityDescriptor capability = new CapabilityDescriptor(
                "trace",
                "v1",
                Set.of(CandidateKind.REPOSITORY),
                1,
                10,
                new CapabilityQuerySchema(List.of()));
        FakeRepositoryCatalogAdapter repositories = new FakeRepositoryCatalogAdapter(
                new RepositoryDescriptor(new RepositoryId("repo-1"), "Repository one"),
                new RepositoryDescriptor(new RepositoryId("repo-2"), "Repository two"));
        return new ValidatedAgentLoop(
                actionPort,
                semanticPort,
                verifier,
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
