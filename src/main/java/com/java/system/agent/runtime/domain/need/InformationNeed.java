package com.java.system.agent.runtime.domain.need;

import com.java.system.agent.runtime.domain.evidence.SemanticTarget;
import com.java.system.agent.runtime.domain.scope.RepositoryId;

import java.util.List;
import java.util.Objects;

/**
 * 一項回答問題所缺的資訊類別
 *
 * <p>描述的是「還缺什麼」而非「該怎麼查」——它不是可執行的 tool call，
 * planning 才會把它轉譯成一個具體的 {@code SemanticCapability} 呼叫</p>
 *
 * <p>{@code targetHints} 只是提示，實際查詢座標由 planning 決定</p>
 */
public record InformationNeed(
        InformationNeedId id,
        InformationNeedType type,
        String question,
        boolean required,
        List<RepositoryId> repositoryCandidates,
        List<SemanticTarget> targetHints) {

    public InformationNeed {
        Objects.requireNonNull(id, "information need ID must not be null");
        Objects.requireNonNull(type, "information need type must not be null");
        Objects.requireNonNull(question, "information need question must not be null");
        Objects.requireNonNull(repositoryCandidates, "repository candidates must not be null");
        Objects.requireNonNull(targetHints, "semantic target hints must not be null");
        question = question.trim();
        if (question.isBlank()) {
            throw new IllegalArgumentException("information need question must not be blank");
        }
        repositoryCandidates = repositoryCandidates.stream()
                .map(candidate -> Objects.requireNonNull(candidate, "repository candidate must not be null"))
                .distinct()
                .sorted()
                .toList();
        targetHints = targetHints.stream()
                .map(target -> Objects.requireNonNull(target, "semantic target hint must not be null"))
                .distinct()
                .sorted()
                .toList();
    }
}
