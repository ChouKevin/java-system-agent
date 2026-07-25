package com.java.system.agent.runtime.application.understanding;

import com.java.system.agent.runtime.domain.scope.RepositoryDiscoverySource;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.domain.scope.RepositoryScope;
import com.java.system.agent.runtime.domain.scope.RepositorySelection;
import com.java.system.agent.runtime.port.out.QuestionUnderstanding;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把問題理解階段挑出的 repository 候選，對照 repository catalog 驗證後轉為 {@link RepositoryScope}
 *
 * <p>由（尚未加入的）orchestrator 在 {@code QuestionUnderstandingPort} 產出
 * {@link QuestionUnderstanding} 之後呼叫，是未經驗證的 LLM 輸出進入 kernel 前的最後一道守門：
 * 候選若不在 catalog 內就直接捨棄，不讓它進入分析範圍</p>
 *
 * <p>當所有候選都被捨棄時回傳 {@link Optional#empty()}，而不是一個空的 scope
 * {@link RepositoryScope#of} 本身接受空集合，真正拒絕空 scope 的是
 * {@code AnalysisExecutionCommand} 的建構子，所以空值必須在進入 kernel 之前就被辨識出來</p>
 */
public final class RepositoryScopeResolver {

    private static final String SELECTION_REASON =
            "Selected as a candidate by the question understanding step";

    private RepositoryScopeResolver() {
    }

    public static Optional<RepositoryScope> resolve(
            QuestionUnderstanding understanding,
            List<RepositoryDescriptor> catalog) {
        Objects.requireNonNull(understanding, "question understanding must not be null");
        Objects.requireNonNull(catalog, "repository catalog must not be null");

        Set<RepositoryId> catalogRepositoryIds = catalog.stream()
                .map(RepositoryDescriptor::repositoryId)
                .collect(Collectors.toUnmodifiableSet());

        List<RepositorySelection> selections = understanding.candidateRepositoryIds().stream()
                .filter(catalogRepositoryIds::contains)
                .map(RepositoryScopeResolver::toSelection)
                .toList();

        if (selections.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RepositoryScope.of(selections));
    }

    private static RepositorySelection toSelection(RepositoryId repositoryId) {
        return new RepositorySelection(
                repositoryId,
                SELECTION_REASON,
                true,
                RepositoryDiscoverySource.QUESTION_UNDERSTANDING);
    }
}
