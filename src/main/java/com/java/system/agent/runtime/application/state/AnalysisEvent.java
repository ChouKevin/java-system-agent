package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;
import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.evidence.EvidenceRef;
import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.need.InformationNeedId;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryRevision;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;

import java.util.Objects;

/**
 * attempt 狀態機能接受的九種事件，是 state 階段唯一合法的輸入
 *
 * <p>sealed 到只有九個子型別，讓 {@link DefaultStateReducer#reduce} 可以用窮盡
 * pattern switch 分派、不留 default 分支——新增事件型別會直接讓 reducer 編譯失敗，
 * 逼迫作者補上對應的處理邏輯</p>
 *
 * <p>每個子型別都攜帶 {@code runId}、{@code attemptId}、{@code expectedStateRevision}
 * 三個欄位，供 {@link DefaultStateReducer#validateEnvelope} 做樂觀鎖檢查</p>
 */
public sealed interface AnalysisEvent {

    AnalysisRunId runId();

    AnalysisAttemptId attemptId();

    long expectedStateRevision();

    record ScopeResolved(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            RepositoryScope repositoryScope) implements AnalysisEvent {

        public ScopeResolved {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(repositoryScope, "repository scope must not be null");
        }
    }

    record ScopeExpanded(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            RepositoryDiscovery repositoryDiscovery,
            boolean required) implements AnalysisEvent {

        public ScopeExpanded {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(repositoryDiscovery, "repository discovery must not be null");
        }
    }

    record RevisionPinned(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            RepositoryId repositoryId,
            RepositoryRevision repositoryRevision) implements AnalysisEvent {

        public RevisionPinned {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(repositoryId, "repository ID must not be null");
            Objects.requireNonNull(repositoryRevision, "repository revision must not be null");
        }
    }

    record NeedRegistered(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            InformationNeed informationNeed) implements AnalysisEvent {

        public NeedRegistered {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(informationNeed, "information need must not be null");
        }
    }

    record EvidenceAccepted(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            InformationNeedId informationNeedId,
            EvidenceRef evidenceRef) implements AnalysisEvent {

        public EvidenceAccepted {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(informationNeedId, "information need ID must not be null");
            Objects.requireNonNull(evidenceRef, "evidence ref must not be null");
        }
    }

    record NeedResolved(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            InformationNeedId informationNeedId) implements AnalysisEvent {

        public NeedResolved {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(informationNeedId, "information need ID must not be null");
        }
    }

    record WarningRecorded(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            AnalysisWarning warning) implements AnalysisEvent {

        public WarningRecorded {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(warning, "analysis warning must not be null");
        }
    }

    record BudgetConsumed(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            BudgetedActivity activity) implements AnalysisEvent {

        public BudgetConsumed {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(activity, "analysis budget activity must not be null");
        }
    }

    record AttemptConcluded(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision,
            AttemptOutcome outcome) implements AnalysisEvent {

        public AttemptConcluded {
            validateEnvelope(runId, attemptId, expectedStateRevision);
            Objects.requireNonNull(outcome, "analysis attempt outcome must not be null");
        }
    }

    private static void validateEnvelope(
            AnalysisRunId runId,
            AnalysisAttemptId attemptId,
            long expectedStateRevision) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(attemptId, "analysis attempt ID must not be null");
        if (expectedStateRevision < 0) {
            throw new IllegalArgumentException("expected state revision must not be negative");
        }
    }
}
