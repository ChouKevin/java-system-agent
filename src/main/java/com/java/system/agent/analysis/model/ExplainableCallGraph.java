package com.java.system.agent.analysis.model;

import java.util.List;
import java.util.Map;

public record ExplainableCallGraph(
        MethodId root,
        List<CallNode> nodes,
        List<CallEdge> edges,
        Map<String, String> relatedClasses,
        FlattenedCallGraph legacyFlattened) {
}
