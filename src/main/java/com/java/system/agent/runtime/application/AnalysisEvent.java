package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.AnalysisRunId;
import com.java.system.agent.runtime.domain.AnalysisWarning;
import com.java.system.agent.runtime.domain.AttemptOutcome;
import com.java.system.agent.runtime.domain.EvidenceRef;
import com.java.system.agent.runtime.domain.InformationNeed;
import com.java.system.agent.runtime.domain.InformationNeedId;
import com.java.system.agent.runtime.domain.RepositoryId;
import com.java.system.agent.runtime.domain.RepositoryRevision;
import com.java.system.agent.runtime.domain.RepositoryScope;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;

import java.util.Objects;

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
            AnalysisBudgetActivity activity) implements AnalysisEvent {

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
