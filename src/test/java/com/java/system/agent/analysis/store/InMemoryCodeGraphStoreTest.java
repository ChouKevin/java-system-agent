package com.java.system.agent.analysis.store;

import com.java.system.agent.analysis.callgraph.CallType;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.MethodId;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCodeGraphStoreTest {

    @Test
    void should_index_methods_edges_and_routes_from_snapshot() {
        MethodId caller = new MethodId("test-repo", "com.example", "OrderService", "createOrder", List.of("String"));
        MethodId callee = new MethodId("test-repo", "com.example", "OrderRepository", "save", List.of("String"));
        CallNode callerNode = new CallNode(caller, "OrderService#createOrder", CallType.INTERNAL_SERVICE,
                "src/main/java/com/example/OrderService.java", 10, 16, Map.of(), "code");
        CallNode calleeNode = new CallNode(callee, "OrderRepository#save", CallType.DATA_ACCESS,
                "src/main/java/com/example/OrderRepository.java", 8, 12, Map.of(), "code");
        CallEdge edge = new CallEdge(caller, callee, "save",
                "src/main/java/com/example/OrderService.java", 12,
                ResolutionStrategy.SPRING_BEAN_BY_TYPE, 0.95, List.of("TARGET_METHOD_FOUND"), List.of());
        RouteNode route = new RouteNode("test-repo", "POST", "/orders", caller);
        CodeGraphSnapshot snapshot = new CodeGraphSnapshot(
                "test-repo", "main", "abc123", List.of(callerNode, calleeNode), List.of(edge),
                List.of(route), List.of());
        InMemoryCodeGraphStore store = new InMemoryCodeGraphStore();

        store.saveSnapshot(snapshot);

        Optional<CallNode> foundMethod = store.findMethod(callee);
        assertTrue(foundMethod.isPresent());
        assertEquals("OrderRepository", foundMethod.get().methodId().className());
        assertEquals(List.of(edge), store.findOutgoingEdges(caller));
        assertEquals(List.of(edge), store.findIncomingEdges(callee));
        assertEquals(List.of(route), store.findRoutes("test-repo", "POST", "/orders"));
    }

    @Test
    void should_return_empty_results_before_snapshot_is_saved() {
        MethodId methodId = new MethodId("test-repo", "com.example", "MissingService", "missing", List.of());
        InMemoryCodeGraphStore store = new InMemoryCodeGraphStore();

        assertTrue(store.findMethod(methodId).isEmpty());
        assertTrue(store.findOutgoingEdges(methodId).isEmpty());
        assertTrue(store.findIncomingEdges(methodId).isEmpty());
        assertTrue(store.findRoutes("test-repo", "GET", "/missing").isEmpty());
    }
}
