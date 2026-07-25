package com.java.system.agent.runtime.application.lifecycle;

import com.java.system.agent.runtime.application.state.AnalysisEvent;
import com.java.system.agent.runtime.application.state.AnalysisTransitionCommitException;
import com.java.system.agent.runtime.application.state.BudgetedActivity;
import com.java.system.agent.runtime.application.state.TransitionCommitter;
import com.java.system.agent.runtime.domain.run.AnalysisAttempt;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.in.AnalysisExecutionCommand;
import com.java.system.agent.runtime.port.out.AnalysisAttemptIdGenerator;
import com.java.system.agent.runtime.port.out.AnalysisCancellationPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 掌管 attempt 生命週期的唯一擁有者：準備、revision 釘選、mismatch 後重啟與收斂
 *
 * <p>由 {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 在迴圈的
 * lifecycle 階段呼叫，內部所有狀態變更都經由
 * {@link com.java.system.agent.runtime.application.state.TransitionCommitter} 落地</p>
 *
 * <p>是唯一產生新 {@code AnalysisAttemptId} 的元件——第一個 attempt 的 ID 來自呼叫端的
 * command，但 revision mismatch 之後的重啟 attempt ID 一律經由
 * {@code AnalysisAttemptIdGenerator} 在此類別內鑄造，沒有其他生產者</p>
 */
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
        return start(command, false, Optional.empty());
    }

    AttemptLifecycle startForExecution(AnalysisExecutionCommand command) {
        return start(command, true, Optional.empty());
    }

    public AttemptLifecycle startForExecution(
            AnalysisExecutionCommand command,
            AnalysisCancellationPort analysisCancellationPort) {
        Objects.requireNonNull(analysisCancellationPort, "analysis cancellation port must not be null");
        return start(command, true, Optional.of(analysisCancellationPort));
    }

    private AttemptLifecycle start(
            AnalysisExecutionCommand command,
            boolean translateTransitionFailures,
            Optional<AnalysisCancellationPort> analysisCancellationPort) {
        Objects.requireNonNull(command, "analysis execution command must not be null");
        Objects.requireNonNull(analysisCancellationPort, "analysis cancellation port must not be null");
        AttemptBudget freshBudget = freshBudget(command.attemptBudget());
        AnalysisRun run = AnalysisRun.start(command.runId(), AnalysisAttempt.start(
                command.firstAttemptId(), RevisionVector.empty(), freshBudget));
        AttemptState state = AttemptState.initial(command.runId(), command.firstAttemptId(), freshBudget);
        AttemptLifecycle initialLifecycle = new AttemptLifecycle(run, state, 0);
        return prepare(
                run,
                state,
                0,
                command.initialScope(),
                command.informationNeeds(),
                translateTransitionFailures ? Optional.of(initialLifecycle) : Optional.empty(),
                translateTransitionFailures,
                analysisCancellationPort);
    }

    public AttemptLifecycle restartAfterRevisionMismatch(
            AttemptLifecycle lifecycle,
            AnalysisExecutionCommand command) {
        return restartAfterRevisionMismatch(lifecycle, command, Optional.empty());
    }

    public AttemptLifecycle restartAfterRevisionMismatchForExecution(
            AttemptLifecycle lifecycle,
            AnalysisExecutionCommand command,
            AnalysisCancellationPort analysisCancellationPort) {
        Objects.requireNonNull(analysisCancellationPort, "analysis cancellation port must not be null");
        return restartAfterRevisionMismatch(lifecycle, command, Optional.of(analysisCancellationPort));
    }

    private AttemptLifecycle restartAfterRevisionMismatch(
            AttemptLifecycle lifecycle,
            AnalysisExecutionCommand command,
            Optional<AnalysisCancellationPort> analysisCancellationPort) {
        Objects.requireNonNull(lifecycle, "attempt lifecycle must not be null");
        Objects.requireNonNull(command, "analysis execution command must not be null");
        Objects.requireNonNull(analysisCancellationPort, "analysis cancellation port must not be null");
        AttemptConclusionValidator.validateActive(lifecycle);
        AttemptRestartValidator.validate(lifecycle, command);
        if (lifecycle.revisionRestartCount() >= 1) {
            throw new IllegalArgumentException("analysis attempt has already been restarted for a revision mismatch");
        }

        AttemptState staleState = commit(lifecycle.state(), new AnalysisEvent.AttemptConcluded(
                lifecycle.state().runId(),
                lifecycle.state().attemptId(),
                lifecycle.state().stateRevision(),
                AttemptOutcome.STALE));
        AnalysisRun staleRun = lifecycle.run().concludeCurrentAttempt(
                staleState.revisionVector(), staleState.budget(), AttemptOutcome.STALE);
        AttemptLifecycle staleLifecycle = new AttemptLifecycle(
                staleRun, staleState, lifecycle.revisionRestartCount());
        AnalysisAttemptId nextAttemptId = nextAttemptId(staleLifecycle, lifecycle.run().id());
        AttemptBudget freshBudget = freshBudget(command.attemptBudget());
        AnalysisRun restartedRun;
        try {
            restartedRun = staleRun.replaceCurrentAttempt(
                    staleRun.currentAttempt(),
                    AnalysisAttempt.start(nextAttemptId, RevisionVector.empty(), freshBudget));
        } catch (IllegalArgumentException exception) {
            throw new AttemptLifecycleExternalFailureException(
                    "replacement analysis attempt ID is invalid", staleLifecycle, exception);
        }
        AttemptState restartedState = AttemptState.initial(
                restartedRun.id(), nextAttemptId, freshBudget);
        return prepare(
                restartedRun,
                restartedState,
                lifecycle.revisionRestartCount() + 1,
                lifecycle.state().repositoryScope(),
                command.informationNeeds(),
                Optional.of(staleLifecycle),
                true,
                analysisCancellationPort);
    }

    public AttemptLifecycle pinDiscoveredRepository(
            AttemptLifecycle lifecycle,
            RepositoryId repositoryId) {
        Objects.requireNonNull(lifecycle, "attempt lifecycle must not be null");
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        AttemptConclusionValidator.validateActive(lifecycle);
        if (!lifecycle.state().repositoryScope().contains(repositoryId)) {
            throw new IllegalArgumentException("discovered repository must already be in the repository scope");
        }
        if (lifecycle.state().revisionVector().revisionOf(repositoryId).isPresent()) {
            return lifecycle;
        }
        return probeAndPin(lifecycle, repositoryId, true, Optional.empty());
    }

    public AttemptLifecycle conclude(
            AttemptLifecycle lifecycle,
            AttemptOutcome attemptOutcome,
            RunOutcome analysisOutcome) {
        Objects.requireNonNull(lifecycle, "attempt lifecycle must not be null");
        Objects.requireNonNull(attemptOutcome, "analysis attempt outcome must not be null");
        Objects.requireNonNull(analysisOutcome, "analysis outcome must not be null");
        AttemptConclusionValidator.validateActive(lifecycle);
        AttemptConclusionValidator.validateCompatibleOutcomes(attemptOutcome, analysisOutcome);

        AttemptState concludedState = commit(lifecycle.state(), new AnalysisEvent.AttemptConcluded(
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
            AttemptState initialState,
            int revisionRestartCount,
            RepositoryScope repositoryScope,
            List<InformationNeed> informationNeeds,
            Optional<AttemptLifecycle> lastCommittedBeforeScope,
            boolean translateTransitionFailures,
            Optional<AnalysisCancellationPort> analysisCancellationPort) {
        Objects.requireNonNull(analysisCancellationPort, "analysis cancellation port must not be null");
        AttemptState scopedState;
        try {
            scopedState = commit(initialState, new AnalysisEvent.ScopeResolved(
                    initialState.runId(),
                    initialState.attemptId(),
                    initialState.stateRevision(),
                    repositoryScope));
        } catch (AnalysisTransitionCommitException exception) {
            throwWithLifecycleIfAvailable(
                    "repository scope preparation failed",
                    lastCommittedBeforeScope,
                    exception);
            throw exception;
        }
        AttemptLifecycle lifecycle = new AttemptLifecycle(run, scopedState, revisionRestartCount);
        for (RepositoryId repositoryId : repositoryScope.repositoryIds()) {
            if (translateTransitionFailures && !lifecycle.state().budget().hasStepRemaining()) {
                throw new AttemptPreparationBudgetExhaustedException(lifecycle);
            }
            lifecycle = probeAndPin(
                    lifecycle, repositoryId, translateTransitionFailures, analysisCancellationPort);
        }
        for (InformationNeed informationNeed : informationNeeds) {
            AttemptState registeredState;
            try {
                registeredState = commit(lifecycle.state(), new AnalysisEvent.NeedRegistered(
                        lifecycle.state().runId(),
                        lifecycle.state().attemptId(),
                        lifecycle.state().stateRevision(),
                        informationNeed));
            } catch (AnalysisTransitionCommitException exception) {
                throwWithLifecycleIfRequested(
                        "information need preparation failed",
                        lifecycle,
                        translateTransitionFailures,
                        exception);
                throw exception;
            }
            lifecycle = new AttemptLifecycle(
                    lifecycle.run(), registeredState, lifecycle.revisionRestartCount());
        }
        return lifecycle;
    }

    private AttemptLifecycle probeAndPin(
            AttemptLifecycle lifecycle,
            RepositoryId repositoryId,
            boolean carryTransitionFailures,
            Optional<AnalysisCancellationPort> analysisCancellationPort) {
        Objects.requireNonNull(analysisCancellationPort, "analysis cancellation port must not be null");
        throwIfPreparationCancellationRequested(analysisCancellationPort, lifecycle);
        AttemptState probedState;
        try {
            probedState = commit(lifecycle.state(), new AnalysisEvent.BudgetConsumed(
                    lifecycle.state().runId(),
                    lifecycle.state().attemptId(),
                    lifecycle.state().stateRevision(),
                    BudgetedActivity.REVISION_PROBE));
        } catch (AnalysisTransitionCommitException exception) {
            throwWithLifecycleIfRequested(
                    "repository revision probe preparation failed",
                    lifecycle,
                    carryTransitionFailures,
                    exception);
            throw exception;
        }
        AttemptLifecycle probedLifecycle = new AttemptLifecycle(
                lifecycle.run(), probedState, lifecycle.revisionRestartCount());
        RepositoryRevisionResult revisionResult = currentRevision(probedLifecycle, repositoryId);
        if (revisionResult.revision().isPresent()) {
            try {
                AttemptState pinnedState = commit(probedState, new AnalysisEvent.RevisionPinned(
                        probedState.runId(),
                        probedState.attemptId(),
                        probedState.stateRevision(),
                        repositoryId,
                        revisionResult.revision().orElseThrow()));
                return new AttemptLifecycle(
                        lifecycle.run(), pinnedState, lifecycle.revisionRestartCount());
            } catch (AnalysisTransitionCommitException exception) {
                throwWithLifecycleIfRequested(
                        "repository revision pinning failed",
                        probedLifecycle,
                        carryTransitionFailures,
                        exception);
                throw exception;
            }
        }
        throw new AttemptPreparationException(
                probedLifecycle, revisionResult.failure().orElseThrow());
    }

    private void throwIfPreparationCancellationRequested(
            Optional<AnalysisCancellationPort> analysisCancellationPort,
            AttemptLifecycle lifecycle) {
        boolean cancellationRequested;
        try {
            cancellationRequested = analysisCancellationPort
                    .map(port -> port.isCancellationRequested(lifecycle.run().id()))
                    .orElse(false);
        } catch (RuntimeException exception) {
            throw new AttemptLifecycleExternalFailureException(
                    "analysis cancellation check failed", lifecycle, exception);
        }
        if (cancellationRequested) {
            throw new AttemptPreparationCancelledException(lifecycle);
        }
    }

    private void throwWithLifecycleIfAvailable(
            String message,
            Optional<AttemptLifecycle> lifecycle,
            AnalysisTransitionCommitException cause) {
        if (lifecycle.isPresent()) {
            throw new AttemptLifecycleExternalFailureException(
                    message, lifecycle.orElseThrow(), cause);
        }
    }

    private void throwWithLifecycleIfRequested(
            String message,
            AttemptLifecycle lifecycle,
            boolean requested,
            AnalysisTransitionCommitException cause) {
        if (requested) {
            throw new AttemptLifecycleExternalFailureException(message, lifecycle, cause);
        }
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

    private AttemptState commit(AttemptState state, AnalysisEvent event) {
        return transitionCommitter.apply(state, event);
    }

    private AttemptBudget freshBudget(AttemptBudget attemptBudget) {
        Objects.requireNonNull(attemptBudget, "analysis attempt budget must not be null");
        return AttemptBudget.of(attemptBudget.maxSteps(), attemptBudget.maxSemanticCalls());
    }

}
