package com.java.system.agent.runtime.port.in;

import com.java.system.agent.runtime.domain.answer.Answer;
import com.java.system.agent.runtime.domain.run.AnalysisRun;
import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RevisionVector;

import java.util.Objects;
import java.util.Set;

/**
 * {@link AnswerQuestionUseCase} 的輸出：組合並驗證完成的回答，連同其分析過程結果
 *
 * <p>與 {@code AnalysisExecutionResult} 的關係——後者是 kernel 的執行結果，
 * 這裡在其外再包一層 {@link Answer}，是回答離開 runtime 的唯一出口</p>
 *
 * <p>{@code revisionVector} 一律取自 kernel 結束後的 {@code AttemptState}，由 runtime
 * 蓋章、不是模型陳述的字串；{@code driftedRepositories} 是相對於這個 thread 上一輪
 * 記憶的 revision 發生變動的 repository，沒有上一輪可比對時為空集合</p>
 */
public record AnswerQuestionResult(
        AnalysisRun run,
        AttemptState finalState,
        Answer answer,
        RunOutcome outcome,
        AnalysisTerminationReason reason,
        RevisionVector revisionVector,
        Set<RepositoryId> driftedRepositories) {

    public AnswerQuestionResult {
        Objects.requireNonNull(run, "analysis run must not be null");
        Objects.requireNonNull(finalState, "final analysis state must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(outcome, "analysis run outcome must not be null");
        Objects.requireNonNull(reason, "analysis termination reason must not be null");
        Objects.requireNonNull(revisionVector, "revision vector must not be null");
        Objects.requireNonNull(driftedRepositories, "drifted repositories must not be null");
        driftedRepositories = Set.copyOf(driftedRepositories);
    }
}
