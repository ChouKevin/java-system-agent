package com.java.system.agent.semantic.adapter.fake;

import com.java.system.agent.runtime.port.out.SemanticQuery;
import com.java.system.agent.runtime.port.out.SemanticQueryPort;
import com.java.system.agent.runtime.port.out.SemanticQueryResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FakeSemanticQueryAdapter implements SemanticQueryPort {

    private final Map<SemanticQuery, List<SemanticQueryResult>> scenarios = new LinkedHashMap<>();
    private final Map<SemanticQuery, Integer> sequencePositions = new LinkedHashMap<>();

    public synchronized FakeSemanticQueryAdapter register(SemanticQuery query, SemanticQueryResult result) {
        Objects.requireNonNull(query, "semantic query must not be null");
        Objects.requireNonNull(result, "semantic query result must not be null");
        return registerSequence(query, result);
    }

    public synchronized FakeSemanticQueryAdapter registerSequence(
            SemanticQuery query,
            SemanticQueryResult... results) {
        Objects.requireNonNull(query, "semantic query must not be null");
        Objects.requireNonNull(results, "semantic query result sequence must not be null");
        if (results.length < 1) {
            throw new IllegalArgumentException("semantic query result sequence must not be empty");
        }
        for (SemanticQueryResult result : results) {
            Objects.requireNonNull(result, "semantic query result sequence must not contain null elements");
        }
        scenarios.put(query, List.copyOf(Arrays.asList(results)));
        sequencePositions.put(query, 0);
        return this;
    }

    @Override
    public synchronized SemanticQueryResult query(SemanticQuery query) {
        Objects.requireNonNull(query, "semantic query must not be null");
        Optional<List<SemanticQueryResult>> scenario = Optional.ofNullable(scenarios.get(query));
        if (scenario.isEmpty()) {
            throw new IllegalArgumentException(
                    "no fake semantic scenario is registered for capability " + query.capabilityName());
        }
        List<SemanticQueryResult> results = scenario.orElseThrow();
        int position = sequencePositions.getOrDefault(query, 0);
        int resultIndex = Math.min(position, results.size() - 1);
        sequencePositions.put(query, position + 1);
        return results.get(resultIndex);
    }
}
