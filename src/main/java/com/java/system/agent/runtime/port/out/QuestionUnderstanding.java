package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.need.InformationNeed;
import com.java.system.agent.runtime.domain.scope.RepositoryId;

import java.util.List;
import java.util.Objects;

/**
 * {@link QuestionUnderstandingPort} 對一個問題的理解結果
 *
 * <p>{@code candidateRepositoryIds} 是縮小後、值得放進 scope 的 repository 候選，
 * {@code needs} 是接下來要交給 kernel 執行的 {@link InformationNeed} 清單</p>
 */
public record QuestionUnderstanding(
        List<RepositoryId> candidateRepositoryIds,
        List<InformationNeed> needs) {

    public QuestionUnderstanding {
        Objects.requireNonNull(candidateRepositoryIds, "candidate repository IDs must not be null");
        Objects.requireNonNull(needs, "information needs must not be null");
    }
}
