package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.answer.Answer;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.RunOutcome;

import java.util.Objects;

/**
 * {@link AnswerQuestionUseCase} 的輸出：組合並驗證完成的回答，連同其分析過程結果
 *
 * <p>與 {@code AnalysisExecutionResult} 的關係——後者是 kernel 的執行結果，
 * 這裡在其外再包一層 {@link Answer}，是回答離開 runtime 的唯一出口</p>
 */
public record AnswerQuestionResult(
        AnalysisRun run,
        AttemptState finalState,
        Answer answer,
        RunOutcome outcome,
        AnalysisTerminationReason reason) {

    public AnswerQuestionResult {
        Objects.requireNonNull(run, "analysis run must not be null");
        Objects.requireNonNull(finalState, "final analysis state must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(outcome, "analysis run outcome must not be null");
        Objects.requireNonNull(reason, "analysis termination reason must not be null");
    }
}
