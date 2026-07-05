package com.java.system.agent.analysis.store;

import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import org.springframework.util.CollectionUtils;

import java.util.List;

public record CodeGraphSnapshot(
        String repoId,
        String branch,
        String commitSha,
        List<CallNode> nodes,
        List<CallEdge> edges,
        List<RouteNode> routes,
        List<SqlMappingNode> sqlMappings) {

    public CodeGraphSnapshot {
        nodes = CollectionUtils.isEmpty(nodes) ? List.of() : List.copyOf(nodes);
        edges = CollectionUtils.isEmpty(edges) ? List.of() : List.copyOf(edges);
        routes = CollectionUtils.isEmpty(routes) ? List.of() : List.copyOf(routes);
        sqlMappings = CollectionUtils.isEmpty(sqlMappings) ? List.of() : List.copyOf(sqlMappings);
    }
}
