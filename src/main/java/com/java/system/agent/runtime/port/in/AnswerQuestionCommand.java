package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.need.Goal;
import com.java.system.agent.runtime.domain.run.AnalysisAttemptId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.AttemptBudget;

import java.util.Objects;

/**
 * {@link AnswerQuestionUseCase} 的輸入：一個尚待理解的原始問題
 *
 * <p>與 {@code AnalysisExecutionCommand} 不同——這裡的呼叫端只帶著問題本身，
 * repository scope 與 information needs 由理解問題階段推導，不是呼叫端先備妥的</p>
 */
public record AnswerQuestionCommand(
        AnalysisRunId runId,
        AnalysisAttemptId firstAttemptId,
        String question,
        Goal goal,
        AttemptBudget budget) {

    public AnswerQuestionCommand {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        Objects.requireNonNull(firstAttemptId, "first analysis attempt ID must not be null");
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(goal, "analysis goal must not be null");
        Objects.requireNonNull(budget, "analysis attempt budget must not be null");
        question = question.trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
    }
}
