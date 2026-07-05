package com.java.system.agent.analysis.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.analysis.callgraph.CallType;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.MethodId;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileCodeGraphStoreTest {

    @Test
    void should_save_and_load_snapshot(@TempDir Path tempDir) {
        Path snapshotFile = tempDir.resolve("code-graph.json");
        MethodId caller = new MethodId("test-repo", "com.example", "OrderService", "createOrder", List.of());
        MethodId callee = new MethodId("test-repo", "com.example", "OrderRepository", "save", List.of());
        CallNode callerNode = new CallNode(caller, "OrderService#createOrder", CallType.INTERNAL_SERVICE,
                Map.of(), "code");
        CallNode calleeNode = new CallNode(callee, "OrderRepository#save", CallType.DATA_ACCESS,
                Map.of(), "code");
        CallEdge edge = new CallEdge(caller, callee, "save", 12,
                ResolutionStrategy.SPRING_BEAN_BY_TYPE, 0.95, List.of("TARGET_METHOD_FOUND"), List.of());
        RouteNode route = new RouteNode("test-repo", "POST", "/orders", caller);
        CodeGraphSnapshot snapshot = new CodeGraphSnapshot("test-repo", "main", "abc123",
                List.of(callerNode, calleeNode), List.of(edge), List.of(route), List.of());
        JsonFileCodeGraphStore writer = new JsonFileCodeGraphStore(snapshotFile, new ObjectMapper());

        writer.saveSnapshot(snapshot);

        JsonFileCodeGraphStore reader = new JsonFileCodeGraphStore(snapshotFile, new ObjectMapper());
        assertTrue(reader.findMethod(callee).isPresent());
        assertEquals(List.of(edge), reader.findOutgoingEdges(caller));
        assertEquals(List.of(edge), reader.findIncomingEdges(callee));
        assertEquals(List.of(route), reader.findRoutes("test-repo", "POST", "/orders"));
    }

    @Test
    void should_return_empty_results_when_file_does_not_exist(@TempDir Path tempDir) {
        MethodId methodId = new MethodId("test-repo", "com.example", "MissingService", "missing", List.of());
        JsonFileCodeGraphStore store = new JsonFileCodeGraphStore(
                tempDir.resolve("missing-code-graph.json"), new ObjectMapper());

        assertTrue(store.findMethod(methodId).isEmpty());
        assertTrue(store.findOutgoingEdges(methodId).isEmpty());
        assertTrue(store.findIncomingEdges(methodId).isEmpty());
        assertTrue(store.findRoutes("test-repo", "GET", "/missing").isEmpty());
    }
}
