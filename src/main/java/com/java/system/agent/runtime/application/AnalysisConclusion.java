package com.java.system.agent.runtime.application;

import com.java.system.agent.runtime.domain.run.AttemptOutcome;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.port.in.AnalysisTerminationReason;

import java.util.Objects;

/**
 * {@link AnalysisConclusionMapper} 四個 {@code forXxx} 方法的共同回傳形狀
 *
 * <p>三個欄位分別對應 {@link BoundedAnalysisLoop} 的 {@code conclude} 方法所需的
 * attempt 結果、run 結果與對外公開的終止原因；本身不做任何轉譯，只是把查表選好的
 * 三個值綁在一起傳回</p>
 */
record AnalysisConclusion(
        AttemptOutcome attemptOutcome,
        RunOutcome runOutcome,
        AnalysisTerminationReason reason) {

    AnalysisConclusion {
        Objects.requireNonNull(attemptOutcome, "attempt outcome must not be null");
        Objects.requireNonNull(runOutcome, "run outcome must not be null");
        Objects.requireNonNull(reason, "analysis termination reason must not be null");
    }
}
