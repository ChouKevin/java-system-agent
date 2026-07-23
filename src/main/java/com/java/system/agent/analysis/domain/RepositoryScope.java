package com.java.system.agent.analysis.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

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
}
