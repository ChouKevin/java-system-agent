package com.java.system.agent.analysis.fixture;

import com.java.system.agent.analysis.model.FlattenedCallGraph;
import com.java.system.agent.analysis.model.FlattenedMethodNode;
import org.junit.jupiter.api.Assertions;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

public final class GraphAssert {

    private GraphAssert() {
    }

    public static void assertMatches(FlattenedCallGraph graph, ExpectedGraphSpec spec) {
        Assertions.assertNotNull(graph, "Graph should not be null");
        Assertions.assertEquals(spec.rootSignature(), graph.getRootSignature(), "Root signature should match");

        List<FlattenedMethodNode> methods = graph.getMethods();
        Assertions.assertNotNull(methods, "Graph methods should not be null");
        if (spec.expectedMethodCount() != null) {
            Assertions.assertEquals(
                    spec.expectedMethodCount().intValue(),
                    methods.size(),
                    "Method count should match");
        }
        if (spec.expectedEdgeCount() != null) {
            Assertions.assertEquals(
                    spec.expectedEdgeCount().intValue(),
                    countEdges(methods),
                    "Edge count should match");
        }

        for (ExpectedGraphSpec.NodeSpec nodeSpec : spec.expectedNodes()) {
            Assertions.assertTrue(
                    methods.stream().anyMatch(node -> matchesNode(node, nodeSpec)),
                    () -> "Expected node not found: " + nodeSpec);
        }

        for (ExpectedGraphSpec.EdgeSpec edgeSpec : spec.expectedEdges()) {
            Assertions.assertTrue(
                    methods.stream().anyMatch(node -> matchesEdge(node, edgeSpec)),
                    () -> "Expected edge not found: " + edgeSpec);
        }

        for (ExpectedGraphSpec.SqlSpec sqlSpec : spec.expectedSql()) {
            Assertions.assertTrue(
                    methods.stream().anyMatch(node -> matchesSql(node, sqlSpec)),
                    () -> "Expected SQL not found: " + sqlSpec);
        }
    }

    private static boolean matchesNode(FlattenedMethodNode node, ExpectedGraphSpec.NodeSpec nodeSpec) {
        return contains(node.getSignature(), nodeSpec.signatureContains())
                && matchesType(node, nodeSpec.type())
                && matchesClassName(node, nodeSpec.className());
    }

    private static boolean matchesType(FlattenedMethodNode node, String expectedType) {
        if (!StringUtils.hasText(expectedType)) {
            return true;
        }
        return node.getCallType() != null && expectedType.equals(node.getCallType().name());
    }

    private static boolean matchesClassName(FlattenedMethodNode node, String expectedClassName) {
        if (!StringUtils.hasText(expectedClassName)) {
            return true;
        }
        return expectedClassName.equals(node.getClassName());
    }

    private static boolean matchesEdge(FlattenedMethodNode node, ExpectedGraphSpec.EdgeSpec edgeSpec) {
        if (!contains(node.getSignature(), edgeSpec.fromContains()) || CollectionUtils.isEmpty(node.getCallees())) {
            return false;
        }
        return node.getCallees().stream().anyMatch(callee -> contains(callee, edgeSpec.toContains()));
    }

    private static boolean matchesSql(FlattenedMethodNode node, ExpectedGraphSpec.SqlSpec sqlSpec) {
        return contains(node.getSignature(), sqlSpec.methodContains())
                && contains(node.getCode(), sqlSpec.sqlContains());
    }

    private static boolean contains(String actual, String expectedPart) {
        return !StringUtils.hasText(expectedPart)
                || (actual != null && actual.contains(expectedPart));
    }

    private static int countEdges(List<FlattenedMethodNode> methods) {
        return methods.stream()
                .map(FlattenedMethodNode::getCallees)
                .filter(Objects::nonNull)
                .mapToInt(List::size)
                .sum();
    }
}
