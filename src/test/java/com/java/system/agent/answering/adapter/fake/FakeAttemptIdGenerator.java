package com.java.system.agent.answering.adapter.fake;

import com.java.system.agent.answering.domain.run.AnalysisAttemptId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.port.out.AnalysisAttemptIdGenerator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class FakeAttemptIdGenerator implements AnalysisAttemptIdGenerator {

    private final Deque<AnalysisAttemptId> registeredAttemptIds = new ArrayDeque<>();

    public FakeAttemptIdGenerator register(AnalysisAttemptId... attemptIds) {
        Objects.requireNonNull(attemptIds, "analysis attempt IDs must not be null");
        if (attemptIds.length == 0) {
            throw new IllegalArgumentException("analysis attempt IDs must not be empty");
        }
        Deque<AnalysisAttemptId> validatedAttemptIds = new ArrayDeque<>();
        for (AnalysisAttemptId attemptId : attemptIds) {
            validatedAttemptIds.addLast(Objects.requireNonNull(
                    attemptId, "analysis attempt ID must not be null"));
        }
        registeredAttemptIds.addAll(validatedAttemptIds);
        return this;
    }

    @Override
    public AnalysisAttemptId nextAttemptId(AnalysisRunId runId, int attemptSequence) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        if (attemptSequence < 1) {
            throw new IllegalArgumentException("attempt number must be positive");
        }
        AnalysisAttemptId attemptId = registeredAttemptIds.pollFirst();
        if (Objects.isNull(attemptId)) {
            throw new IllegalStateException("fake analysis attempt ID sequence is exhausted");
        }
        return attemptId;
    }
}
