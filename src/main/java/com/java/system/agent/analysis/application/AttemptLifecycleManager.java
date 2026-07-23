package com.java.system.agent.analysis.application;

import com.java.system.agent.analysis.domain.AnalysisAttempt;
import com.java.system.agent.analysis.domain.AnalysisAttemptId;
import com.java.system.agent.analysis.domain.AnalysisBudget;
import com.java.system.agent.analysis.domain.AnalysisOutcome;
import com.java.system.agent.analysis.domain.AnalysisRun;
import com.java.system.agent.analysis.domain.AnalysisRunId;
import com.java.system.agent.analysis.domain.AnalysisState;
import com.java.system.agent.analysis.domain.AnalysisStatus;
import com.java.system.agent.analysis.domain.AttemptOutcome;
import com.java.system.agent.analysis.domain.InformationNeed;
import com.java.system.agent.analysis.domain.InformationNeedId;
import com.java.system.agent.analysis.domain.RepositoryId;
import com.java.system.agent.analysis.domain.RepositoryScope;
import com.java.system.agent.analysis.domain.RevisionVector;
import com.java.system.agent.analysis.port.in.AnalysisExecutionCommand;
import com.java.system.agent.analysis.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.analysis.port.out.RepositoryRevisionPort;
import com.java.system.agent.analysis.port.out.RepositoryRevisionResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AttemptLifecycleManager {

    private final TransitionCommitter transitionCommitter;
    private final RepositoryRevisionPort repositoryRevisionPort;
    private final AnalysisAttemptIdGenerator analysisAttemptIdGenerator;

    public AttemptLifecycleManager(
            TransitionCommitter transitionCommitter,
            RepositoryRevisionPort repositoryRevisionPort,
            AnalysisAttemptIdGenerator analysisAttemptIdGenerator) {
        this.transitionCommitter = Objects.requireNonNull(
                transitionCommitter, "transition committer must not be null");
        this.repositoryRevisionPort = Objects.requireNonNull(
                repositoryRevisionPort, "repository revision port must not be null");
        this.analysisAttemptIdGenerator = Objects.requireNonNull(
                analysisAttemptIdGenerator, "analysis attempt ID generator must not be null");
    }

    public AttemptLifecycle start(AnalysisExecutionCommand command) {
        Objects.requireNonNull(command, "analysis execution command must not be null");
        AnalysisBudget freshBudget = freshBudget(command.attemptBudget());
        AnalysisRun run = AnalysisRun.start(command.runId(), AnalysisAttempt.start(
                command.firstAttemptId(), RevisionVector.empty(), freshBudget));
        AnalysisState state = AnalysisState.initial(command.runId(), command.firstAttemptId(), freshBudget);
        return prepare(run, state, 0, command.initialScope(), command.informationNeeds());
    }

    public AttemptLifecycle restartAfterRevisionMismatch(
            AttemptLifecycle lifecycle,
            AnalysisExecutionCommand command) {
        Objects.requireNonNull(lifecycle, "attempt lifecycle must not be null");
        Objects.requireNonNull(command, "analysis execution command must not be null");
        validateActiveLifecycle(lifecycle);
        validateRestartCommand(lifecycle, command);
        if (lifecycle.revisionRestartCount() >= 1) {
            throw new IllegalArgumentException("analysis attempt has already been restarted for a revision mismatch");
        }

        AnalysisState staleState = commit(lifecycle.state(), new AnalysisEvent.AttemptConcluded(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                AttemptOutcome.STALE));
        AnalysisRun staleRun = lifecycle.run().concludeCurrentAttempt(
                staleState.revisionVector(), staleState.budget(), AttemptOutcome.STALE);
        AttemptLifecycle staleLifecycle = new AttemptLifecycle(
                staleRun, staleState, lifecycle.revisionRestartCount());
        AnalysisAttemptId nextAttemptId = nextAttemptId(staleLifecycle, lifecycle.run().id());
        AnalysisBudget freshBudget = freshBudget(command.attemptBudget());
        AnalysisRun restartedRun = staleRun.replaceCurrentAttempt(
                staleRun.currentAttempt(),
                AnalysisAttempt.start(nextAttemptId, RevisionVector.empty(), freshBudget));
        AnalysisState restartedState = AnalysisState.initial(
                restartedRun.id(), nextAttemptId, freshBudget);
        return prepare(
                restartedRun,
                restartedState,
                lifecycle.revisionRestartCount() + 1,
                lifecycle.state().repositoryScope(),
                command.informationNeeds());
    }

    public AttemptLifecycle pinDiscoveredRepository(
            AttemptLifecycle lifecycle,
            RepositoryId repositoryId) {
        Objects.requireNonNull(lifecycle, "attempt lifecycle must not be null");
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        validateActiveLifecycle(lifecycle);
        if (!lifecycle.state().repositoryScope().contains(repositoryId)) {
            throw new IllegalArgumentException("discovered repository must already be in the repository scope");
        }
        if (lifecycle.state().revisionVector().revisionOf(repositoryId).isPresent()) {
            return lifecycle;
        }
        return probeAndPin(lifecycle, repositoryId);
    }

    public AttemptLifecycle conclude(
            AttemptLifecycle lifecycle,
            AttemptOutcome attemptOutcome,
            AnalysisOutcome analysisOutcome) {
        Objects.requireNonNull(lifecycle, "attempt lifecycle must not be null");
        Objects.requireNonNull(attemptOutcome, "analysis attempt outcome must not be null");
        Objects.requireNonNull(analysisOutcome, "analysis outcome must not be null");
        validateActiveLifecycle(lifecycle);
        validateCompatibleOutcomes(attemptOutcome, analysisOutcome);

        AnalysisState concludedState = commit(lifecycle.state(), new AnalysisEvent.AttemptConcluded(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                attemptOutcome));
        AnalysisRun concludedRun = lifecycle.run().concludeCurrentAttempt(
                concludedState.revisionVector(), concludedState.budget(), attemptOutcome)
                .conclude(analysisOutcome);
        return new AttemptLifecycle(
                concludedRun, concludedState, lifecycle.revisionRestartCount());
    }

    private AttemptLifecycle prepare(
            AnalysisRun run,
            AnalysisState initialState,
            int revisionRestartCount,
            RepositoryScope repositoryScope,
            List<InformationNeed> informationNeeds) {
        AnalysisState scopedState = commit(initialState, new AnalysisEvent.ScopeResolved(
                initialState.runId(),
                initialState.attemptId(),
                initialState.stateRevision(),
                repositoryScope));
        AttemptLifecycle lifecycle = new AttemptLifecycle(run, scopedState, revisionRestartCount);
        for (RepositoryId repositoryId : repositoryScope.repositoryIds()) {
            lifecycle = probeAndPin(lifecycle, repositoryId);
        }
        for (InformationNeed informationNeed : informationNeeds) {
            AnalysisState registeredState = commit(lifecycle.state(), new AnalysisEvent.NeedRegistered(
                    lifecycle.state().runId(),
                    lifecycle.state().attemptId(),
                    lifecycle.state().stateRevision(),
                    informationNeed));
            lifecycle = new AttemptLifecycle(
                    lifecycle.run(), registeredState, lifecycle.revisionRestartCount());
        }
        return lifecycle;
    }

    private AttemptLifecycle probeAndPin(AttemptLifecycle lifecycle, RepositoryId repositoryId) {
        AnalysisState probedState = commit(lifecycle.state(), new AnalysisEvent.BudgetConsumed(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                AnalysisBudgetActivity.REVISION_PROBE));
        AttemptLifecycle probedLifecycle = new AttemptLifecycle(
                lifecycle.run(), probedState, lifecycle.revisionRestartCount());
        RepositoryRevisionResult revisionResult = currentRevision(probedLifecycle, repositoryId);
        if (revisionResult.revision().isPresent()) {
            AnalysisState pinnedState = commit(probedState, new AnalysisEvent.RevisionPinned(
                    probedState.runId(),
                    probedState.attemptId(),
                    probedState.stateRevision(),
                    repositoryId,
                    revisionResult.revision().orElseThrow()));
            return new AttemptLifecycle(
                    lifecycle.run(), pinnedState, lifecycle.revisionRestartCount());
        }
        throw new AttemptPreparationException(
                probedLifecycle, revisionResult.failure().orElseThrow());
    }

    private AnalysisAttemptId nextAttemptId(
            AttemptLifecycle staleLifecycle,
            AnalysisRunId runId) {
        try {
            return Objects.requireNonNull(
                    analysisAttemptIdGenerator.nextAttemptId(runId, 2),
                    "analysis attempt ID generator must return an attempt ID");
        } catch (RuntimeException exception) {
            throw new AttemptLifecycleExternalFailureException(
                    "analysis attempt ID generation failed", staleLifecycle, exception);
        }
    }

    private RepositoryRevisionResult currentRevision(
            AttemptLifecycle probedLifecycle,
            RepositoryId repositoryId) {
        try {
            return Objects.requireNonNull(
                    repositoryRevisionPort.currentRevision(repositoryId),
                    "repository revision port must return a revision result");
        } catch (RuntimeException exception) {
            throw new AttemptLifecycleExternalFailureException(
                    "repository revision preparation failed", probedLifecycle, exception);
        }
    }

    private AnalysisState commit(AnalysisState state, AnalysisEvent event) {
        return transitionCommitter.apply(state, event);
    }

    private AnalysisBudget freshBudget(AnalysisBudget attemptBudget) {
        Objects.requireNonNull(attemptBudget, "analysis attempt budget must not be null");
        return AnalysisBudget.of(attemptBudget.maxSteps(), attemptBudget.maxSemanticCalls());
    }

    private void validateRestartCommand(AttemptLifecycle lifecycle, AnalysisExecutionCommand command) {
        if (!lifecycle.run().id().equals(command.runId())) {
            throw new IllegalArgumentException("analysis execution command belongs to another run");
        }
        if (!lifecycle.run().attempts().getFirst().id().equals(command.firstAttemptId())) {
            throw new IllegalArgumentException(
                    "analysis execution command belongs to another first attempt");
        }
        validateRestartBudget(lifecycle, command);
        validateRestartInformationNeeds(lifecycle, command);
    }

    private void validateRestartBudget(AttemptLifecycle lifecycle, AnalysisExecutionCommand command) {
        AnalysisBudget commandBudget = command.attemptBudget();
        if (!hasSameBudgetLimits(commandBudget, lifecycle.run().currentAttempt().budget())
                || !hasSameBudgetLimits(commandBudget, lifecycle.state().budget())) {
            throw new IllegalArgumentException(
                    "analysis execution command has different attempt budget limits");
        }
    }

    private boolean hasSameBudgetLimits(AnalysisBudget firstBudget, AnalysisBudget secondBudget) {
        return firstBudget.maxSteps() == secondBudget.maxSteps()
                && firstBudget.maxSemanticCalls() == secondBudget.maxSemanticCalls();
    }

    private void validateRestartInformationNeeds(
            AttemptLifecycle lifecycle,
            AnalysisExecutionCommand command) {
        Set<InformationNeedId> registeredNeedIds = new TreeSet<>(
                lifecycle.state().pendingNeeds().keySet());
        registeredNeedIds.addAll(lifecycle.state().resolvedNeedIds());
        Map<InformationNeedId, InformationNeed> commandNeedsById = command.informationNeeds().stream()
                .collect(Collectors.toUnmodifiableMap(InformationNeed::id, Function.identity()));
        if (!registeredNeedIds.equals(commandNeedsById.keySet())) {
            throw new IllegalArgumentException(
                    "analysis execution command has different information need IDs");
        }

        // AnalysisState retains values only for pending needs; resolved need IDs are guarded above.
        for (Map.Entry<InformationNeedId, InformationNeed> registeredPendingNeed
                : lifecycle.state().pendingNeeds().entrySet()) {
            InformationNeed commandNeed = commandNeedsById.get(registeredPendingNeed.getKey());
            if (!registeredPendingNeed.getValue().equals(commandNeed)) {
                throw new IllegalArgumentException(
                        "analysis execution command has a different pending information need");
            }
        }
    }

    private void validateActiveLifecycle(AttemptLifecycle lifecycle) {
        if (lifecycle.run().outcome().isPresent()
                || lifecycle.run().currentAttempt().outcome().isPresent()
                || !isActiveStatus(lifecycle.state().status())) {
            throw new IllegalArgumentException(
                    "analysis operation requires an active lifecycle before applying an active operation");
        }
    }

    private boolean isActiveStatus(AnalysisStatus status) {
        return switch (status) {
            case RECEIVED, UNDERSTANDING, SCOPE_RESOLVING, REVISION_PINNING,
                    PLANNING, EXECUTING, COMPOSING, VERIFYING -> true;
            case STALE, COMPLETED, INCONCLUSIVE, FAILED, CANCELLED -> false;
        };
    }

    private void validateCompatibleOutcomes(
            AttemptOutcome attemptOutcome,
            AnalysisOutcome analysisOutcome) {
        boolean compatible = switch (attemptOutcome) {
            case COMPLETED -> analysisOutcome == AnalysisOutcome.COMPLETED;
            case INCONCLUSIVE, STALE -> analysisOutcome == AnalysisOutcome.INCONCLUSIVE;
            case FAILED -> analysisOutcome == AnalysisOutcome.FAILED;
            case CANCELLED -> analysisOutcome == AnalysisOutcome.CANCELLED;
        };
        if (!compatible) {
            throw new IllegalArgumentException("analysis attempt and run outcomes are not compatible");
        }
    }
}
