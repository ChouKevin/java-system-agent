package com.java.system.agent.answering.domain.handle;

import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.scope.RevisionVector;

import java.util.Objects;

/**
 * Runtime 配發 handle 時固定的 run、attempt 與 revision 範圍
 */
public record HandleBinding(AnalysisRunId runId, AnalysisAttemptId attemptId, RevisionVector revisionVector) {

    public HandleBinding {
        Objects.requireNonNull(runId, "handle binding run ID must not be null");
        Objects.requireNonNull(attemptId, "handle binding attempt ID must not be null");
        Objects.requireNonNull(revisionVector, "handle binding revision vector must not be null");
    }
}
