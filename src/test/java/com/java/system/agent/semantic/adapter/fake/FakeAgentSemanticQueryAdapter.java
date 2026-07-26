package com.java.system.agent.semantic.adapter.fake;

import com.java.system.agent.runtime.port.out.AgentSemanticQuery;
import com.java.system.agent.runtime.port.out.AgentSemanticQueryPort;
import com.java.system.agent.runtime.port.out.AgentSemanticQueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 以精確 typed query 登錄原始語意結果且不提供 fallback 的測試替身
 */
public final class FakeAgentSemanticQueryAdapter implements AgentSemanticQueryPort {

    private final Map<AgentSemanticQuery, AgentSemanticQueryResult> results = new LinkedHashMap<>();
    private final List<AgentSemanticQuery> queries = new ArrayList<>();

    public synchronized FakeAgentSemanticQueryAdapter register(
            AgentSemanticQuery query,
            AgentSemanticQueryResult result) {
        results.put(Objects.requireNonNull(query, "agent semantic query must not be null"),
                Objects.requireNonNull(result, "agent semantic query result must not be null"));
        return this;
    }

    @Override
    public synchronized AgentSemanticQueryResult query(AgentSemanticQuery query) {
        AgentSemanticQuery checkedQuery = Objects.requireNonNull(query, "agent semantic query must not be null");
        queries.add(checkedQuery);
        AgentSemanticQueryResult result = results.get(checkedQuery);
        if (Objects.isNull(result)) {
            throw new IllegalArgumentException("no fake agent semantic result is registered for the query");
        }
        return result;
    }

    public synchronized List<AgentSemanticQuery> queries() {
        return List.copyOf(queries);
    }
}
