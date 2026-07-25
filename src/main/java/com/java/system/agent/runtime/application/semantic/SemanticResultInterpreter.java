package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.application.state.AnalysisEvent;
import com.java.system.agent.runtime.application.state.AnalysisTransitionCommitException;
import com.java.system.agent.runtime.application.state.TransitionCommitter;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import com.java.system.agent.runtime.port.out.SemanticFailure;
import com.java.system.agent.runtime.port.out.SemanticFailureCode;
import com.java.system.agent.runtime.port.out.SemanticQuery;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;
import com.java.system.agent.runtime.port.out.SemanticResultStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 把語意服務回傳的 {@code SemanticQueryResult} 翻譯成 {@link SemanticStepOutcome} 與
 * 對應的狀態變更
 *
 * <p>由 {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 在每次語意
 * 查詢（含重試）之後呼叫；驗證證據與 revision 一致性、視需要接受證據或發現新
 * repository，所有狀態變更都經由 {@link com.java.system.agent.runtime.application.state.TransitionCommitter}
 * 落地，回傳的 {@link SemanticStepResult} 帶著已提交的最新狀態</p>
 *
 * <p>PARTIAL 結果不會被丟棄——它跟 SUCCESS 一樣會把回傳的證據接受為有效證據，只是
 * 額外記一筆警告，讓迴圈可以帶著不完整但可用的證據繼續往下走</p>
 */
public final class SemanticResultInterpreter {

    private final TransitionCommitter transitionCommitter;

    public SemanticResultInterpreter(TransitionCommitter transitionCommitter) {
        this.transitionCommitter = Objects.requireNonNull(
                transitionCommitter, "transition committer must not be null");
    }

    public SemanticStepResult handle(
            AttemptState state,
            SemanticQuery query,
            SemanticQueryResult result,
            boolean retryAllowed) {
        return handleInternal(state, query, result, retryAllowed, NoopCommitTracker.INSTANCE);
    }

    public SemanticStepResult handleForExecution(
            AttemptState state,
            SemanticQuery query,
            SemanticQueryResult result,
            boolean retryAllowed) {
        TrackingCommitTracker commitTracker = new TrackingCommitTracker(state);
        try {
            return handleInternal(state, query, result, retryAllowed, commitTracker);
        } catch (AnalysisTransitionCommitException exception) {
            throw new SemanticResultHandlingException(commitTracker.lastCommittedState(), exception);
        }
    }

    private SemanticStepResult handleInternal(
            AttemptState state,
            SemanticQuery query,
            SemanticQueryResult result,
            boolean retryAllowed,
            CommitTracker commitTracker) {
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(query, "semantic query must not be null");
        Objects.requireNonNull(result, "semantic query result must not be null");
        InformationNeed pendingNeed = query.informationNeed();
        EvidenceValidation validation = SemanticEvidenceValidator.validate(state, pendingNeed, query, result);

        return switch (result.status()) {
            case SUCCESS -> handleSuccess(state, pendingNeed, result, validation, commitTracker);
            case PARTIAL -> handlePartial(state, pendingNeed, result, validation, commitTracker);
            case REVISION_MISMATCH -> handleRevisionMismatch(state, query, result, commitTracker);
            case NOT_READY, TIMEOUT -> handleTransient(state, result, retryAllowed, commitTracker);
            case AMBIGUOUS -> block(state, result, "SEMANTIC_AMBIGUOUS_TARGET", commitTracker);
            case FORBIDDEN -> block(state, result, "SEMANTIC_FORBIDDEN", commitTracker);
            case CAPABILITY_MISSING -> block(state, result, "SEMANTIC_CAPABILITY_MISSING", commitTracker);
            case FAILED -> fail(state, result, commitTracker);
        };
    }

    private SemanticStepResult handleSuccess(
            AttemptState state,
            InformationNeed pendingNeed,
            SemanticQueryResult result,
            EvidenceValidation validation,
            CommitTracker commitTracker) {
        if (validation.outcome() == EvidenceValidationOutcome.STALE) {
            return step(state, SemanticStepOutcome.STALE, result.failure());
        }
        if (validation.outcome() == EvidenceValidationOutcome.PROTOCOL_ERROR) {
            return protocolFailure(state, commitTracker);
        }
        AppliedEvidence applied = applyEvidenceAndDiscoveries(
                state, pendingNeed, validation.validatedEvidence().orElseThrow(), commitTracker);
        AttemptState resolved = commit(applied.state(), new AnalysisEvent.NeedResolved(
                applied.state().runId(),
                applied.state().attemptId(),
                applied.state().stateRevision(),
                pendingNeed.id()), commitTracker);
        return new SemanticStepResult(
                resolved, SemanticStepOutcome.PROGRESSED, Optional.empty(), applied.discoveries());
    }

    private SemanticStepResult handlePartial(
            AttemptState state,
            InformationNeed pendingNeed,
            SemanticQueryResult result,
            EvidenceValidation validation,
            CommitTracker commitTracker) {
        if (validation.outcome() == EvidenceValidationOutcome.STALE) {
            return step(state, SemanticStepOutcome.STALE, result.failure());
        }
        if (validation.outcome() == EvidenceValidationOutcome.PROTOCOL_ERROR) {
            return protocolFailure(state, commitTracker);
        }
        AppliedEvidence applied = applyEvidenceAndDiscoveries(
                state, pendingNeed, validation.validatedEvidence().orElseThrow(), commitTracker);
        AttemptState warned = recordWarningIfAbsent(
                applied.state(), "SEMANTIC_PARTIAL_RESULT", result.failure().orElseThrow(), commitTracker);
        return new SemanticStepResult(
                warned, SemanticStepOutcome.PARTIAL, result.failure(), applied.discoveries());
    }

    private SemanticStepResult handleTransient(
            AttemptState state,
            SemanticQueryResult result,
            boolean retryAllowed,
            CommitTracker commitTracker) {
        if (retryAllowed) {
            return step(state, SemanticStepOutcome.RETRYABLE, result.failure());
        }
        String warningCode = switch (result.status()) {
            case NOT_READY -> "SEMANTIC_NOT_READY";
            case TIMEOUT -> "SEMANTIC_TIMEOUT";
            default -> throw new IllegalArgumentException("semantic status is not transient");
        };
        return block(state, result, warningCode, commitTracker);
    }

    private SemanticStepResult handleRevisionMismatch(
            AttemptState state,
            SemanticQuery query,
            SemanticQueryResult result,
            CommitTracker commitTracker) {
        RepositoryRevision analyzedRevision = result.analyzedRevision().orElseThrow();
        if (analyzedRevision.equals(query.expectedRevision())) {
            return protocolFailure(state, commitTracker);
        }
        return step(state, SemanticStepOutcome.STALE, result.failure());
    }

    private SemanticStepResult block(
            AttemptState state,
            SemanticQueryResult result,
            String warningCode,
            CommitTracker commitTracker) {
        AttemptState warned = recordWarningIfAbsent(
                state, warningCode, result.failure().orElseThrow(), commitTracker);
        return step(warned, SemanticStepOutcome.BLOCKED, result.failure());
    }

    private SemanticStepResult fail(
            AttemptState state,
            SemanticQueryResult result,
            CommitTracker commitTracker) {
        SemanticFailure failure = result.failure().orElseThrow();
        AttemptState warned = recordWarningIfAbsent(
                state, "SEMANTIC_" + failure.code().name(), failure, commitTracker);
        return step(warned, SemanticStepOutcome.FAILED, result.failure());
    }

    private SemanticStepResult protocolFailure(AttemptState state, CommitTracker commitTracker) {
        SemanticFailure failure = new SemanticFailure(
                SemanticFailureCode.PROTOCOL_ERROR,
                "semantic result violates the revision-bound evidence contract",
                false);
        AttemptState warned = recordWarningIfAbsent(
                state, "SEMANTIC_PROTOCOL_ERROR", failure, commitTracker);
        return step(warned, SemanticStepOutcome.FAILED, Optional.of(failure));
    }

    private AppliedEvidence applyEvidenceAndDiscoveries(
            AttemptState state,
            InformationNeed pendingNeed,
            ValidatedEvidence validated,
            CommitTracker commitTracker) {
        AttemptState current = state;
        for (EvidenceRef evidence : validated.newEvidence()) {
            current = commit(current, new AnalysisEvent.EvidenceAccepted(
                    current.runId(), current.attemptId(), current.stateRevision(), pendingNeed.id(), evidence),
                    commitTracker);
        }
        for (RepositoryDiscovery discovery : validated.newDiscoveries()) {
            current = commit(current, new AnalysisEvent.ScopeExpanded(
                    current.runId(), current.attemptId(), current.stateRevision(), discovery, false),
                    commitTracker);
        }
        return new AppliedEvidence(current, validated.newDiscoveries());
    }

    private AttemptState recordWarningIfAbsent(
            AttemptState state,
            String warningCode,
            SemanticFailure failure,
            CommitTracker commitTracker) {
        boolean warningAlreadyRecorded = state.warnings().stream()
                .anyMatch(warning -> warning.code().equals(warningCode));
        if (warningAlreadyRecorded) {
            return state;
        }
        return commit(state, new AnalysisEvent.WarningRecorded(
                state.runId(),
                state.attemptId(),
                state.stateRevision(),
                new AnalysisWarning(warningCode, failure.message())), commitTracker);
    }

    private SemanticStepResult step(
            AttemptState state,
            SemanticStepOutcome disposition,
            Optional<SemanticFailure> failure) {
        return new SemanticStepResult(state, disposition, failure, List.of());
    }

    private AttemptState commit(
            AttemptState state,
            AnalysisEvent event,
            CommitTracker commitTracker) {
        AttemptState committedState = transitionCommitter.apply(state, event);
        commitTracker.record(committedState);
        return committedState;
    }

    private record AppliedEvidence(AttemptState state, List<RepositoryDiscovery> discoveries) {

        private AppliedEvidence {
            Objects.requireNonNull(state, "analysis state must not be null");
            discoveries = List.copyOf(discoveries);
        }
    }
}
