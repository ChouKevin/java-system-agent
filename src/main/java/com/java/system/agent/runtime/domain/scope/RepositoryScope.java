package com.java.system.agent.runtime.domain.scope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 一次 Attempt 涵蓋的 repository 選擇集合
 *
 * <p>{@link #of(Collection)} 一次可接受多個 repository，這是刻意設計——
 * 一個問題本就可能橫跨多個服務；分析過程中只能透過 {@link #expand(RepositorySelection)}
 * 追加新的 repository，且呼叫端必須以已被接受的語意證據為依據，不可任意擴大範圍</p>
 */
public final class RepositoryScope {

    private final SortedMap<RepositoryId, RepositorySelection> selections;

    private RepositoryScope(SortedMap<RepositoryId, RepositorySelection> selections) {
        this.selections = Collections.unmodifiableSortedMap(new TreeMap<>(selections));
    }

    public static RepositoryScope of(Collection<RepositorySelection> selections) {
        Objects.requireNonNull(selections, "repository selections must not be null");
        SortedMap<RepositoryId, RepositorySelection> indexedSelections = new TreeMap<>();
        for (RepositorySelection selection : selections) {
            Objects.requireNonNull(selection, "repository selection must not be null");
            RepositorySelection previous = indexedSelections.putIfAbsent(
                    selection.repositoryId(), selection);
            if (Objects.nonNull(previous)) {
                throw new IllegalArgumentException(
                        "repository is selected more than once: " + selection.repositoryId().value());
            }
        }
        return new RepositoryScope(indexedSelections);
    }

    public RepositoryScope expand(RepositorySelection selection) {
        Objects.requireNonNull(selection, "repository selection must not be null");
        if (selections.containsKey(selection.repositoryId())) {
            throw new IllegalArgumentException(
                    "repository is already in scope: " + selection.repositoryId().value());
        }
        SortedMap<RepositoryId, RepositorySelection> expanded = new TreeMap<>(selections);
        expanded.put(selection.repositoryId(), selection);
        return new RepositoryScope(expanded);
    }

    public boolean contains(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        return selections.containsKey(repositoryId);
    }

    public List<RepositorySelection> selections() {
        return List.copyOf(selections.values());
    }

    public List<RepositoryId> repositoryIds() {
        return List.copyOf(selections.keySet());
    }

    public List<RepositoryId> requiredRepositoryIds() {
        List<RepositoryId> required = new ArrayList<>();
        for (RepositorySelection selection : selections.values()) {
            if (selection.required()) {
                required.add(selection.repositoryId());
            }
        }
        return List.copyOf(required);
    }

    public Optional<RepositorySelection> selection(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        return Optional.ofNullable(selections.get(repositoryId));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RepositoryScope repositoryScope // cs-allow
                && selections.equals(repositoryScope.selections);
    }

    @Override
    public int hashCode() {
        return selections.hashCode();
    }
}
