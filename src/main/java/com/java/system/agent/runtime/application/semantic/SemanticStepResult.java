package com.java.system.agent.runtime.application.semantic;

import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.port.out.RepositoryDiscovery;
import com.java.system.agent.runtime.port.out.SemanticFailure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SemanticResultInterpreter} 處理完一次語意查詢後的結果
 *
 * <p>攜帶已提交的最新狀態、判定結果（{@link SemanticStepOutcome}）、若失敗則附上的
 * {@code SemanticFailure}，以及本次驗證出的新 repository 發現，供
 * {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 決定下一步</p>
 */
public record SemanticStepResult(
        AttemptState state,
        SemanticStepOutcome disposition,
        Optional<SemanticFailure> failure,
        List<RepositoryDiscovery> newDiscoveries) {

    public SemanticStepResult {
        Objects.requireNonNull(state, "analysis state must not be null");
        Objects.requireNonNull(disposition, "semantic step disposition must not be null");
        Objects.requireNonNull(failure, "semantic failure must not be null");
        Objects.requireNonNull(newDiscoveries, "repository discoveries must not be null");
        newDiscoveries = List.copyOf(newDiscoveries);
    }
}
