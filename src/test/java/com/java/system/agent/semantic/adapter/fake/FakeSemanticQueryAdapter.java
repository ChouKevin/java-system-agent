package com.java.system.agent.semantic.adapter.fake;

import com.java.system.agent.analysis.port.out.SemanticQuery;
import com.java.system.agent.analysis.port.out.SemanticQueryPort;
import com.java.system.agent.analysis.port.out.SemanticQueryResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FakeSemanticQueryAdapter implements SemanticQueryPort {

    private final Map<SemanticQuery, SemanticQueryResult> scenarios = new LinkedHashMap<>();

    public FakeSemanticQueryAdapter register(SemanticQuery query, SemanticQueryResult result) {
        Objects.requireNonNull(query, "semantic query must not be null");
        Objects.requireNonNull(result, "semantic query result must not be null");
        scenarios.put(query, result);
        return this;
    }

    @Override
    public SemanticQueryResult query(SemanticQuery query) {
        Objects.requireNonNull(query, "semantic query must not be null");
        SemanticQueryResult result = scenarios.get(query);
        if (Objects.isNull(result)) {
            throw new IllegalArgumentException(
                    "no fake semantic scenario is registered for capability " + query.capabilityName());
        }
        return result;
    }
}
