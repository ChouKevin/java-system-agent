package com.java.system.agent.analysis.fixture;

import java.util.List;

public record ExpectedGraphSpec(
        String rootSignature,
        Integer expectedMethodCount,
        Integer expectedEdgeCount,
        List<NodeSpec> expectedNodes,
        List<EdgeSpec> expectedEdges,
        List<SqlSpec> expectedSql) {

    public record NodeSpec(String signatureContains, String type, String className) {
    }

    public record EdgeSpec(String fromContains, String toContains) {
    }

    public record SqlSpec(String methodContains, String sqlContains) {
    }
}
