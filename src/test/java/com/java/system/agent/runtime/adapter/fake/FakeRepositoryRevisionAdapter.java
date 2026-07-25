package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.port.out.RepositoryRevisionPort;
import com.java.system.agent.runtime.port.out.RepositoryRevisionResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.TreeMap;

public final class FakeRepositoryRevisionAdapter implements RepositoryRevisionPort {

    private final TreeMap<RepositoryId, Deque<RepositoryRevisionResult>> scenarios = new TreeMap<>();

    public FakeRepositoryRevisionAdapter register(
            RepositoryId repositoryId,
            RepositoryRevisionResult... results) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Objects.requireNonNull(results, "repository revision results must not be null");
        if (results.length == 0) {
            throw new IllegalArgumentException("repository revision results must not be empty");
        }
        Deque<RepositoryRevisionResult> registeredResults = new ArrayDeque<>();
        for (RepositoryRevisionResult result : results) {
            registeredResults.addLast(Objects.requireNonNull(
                    result, "repository revision result must not be null"));
        }
        scenarios.put(repositoryId, registeredResults);
        return this;
    }

    @Override
    public RepositoryRevisionResult currentRevision(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository ID must not be null");
        Deque<RepositoryRevisionResult> registeredResults = scenarios.get(repositoryId);
        if (Objects.isNull(registeredResults)) {
            throw new IllegalStateException(
                    "no fake repository revision scenario is registered for " + repositoryId.value());
        }
        if (registeredResults.size() == 0) {
            throw new IllegalStateException(
                    "fake repository revision scenario is exhausted for " + repositoryId.value());
        }
        if (registeredResults.size() > 1) {
            return registeredResults.removeFirst();
        }
        return registeredResults.peekFirst();
    }
}
