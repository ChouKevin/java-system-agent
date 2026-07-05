package com.java.system.agent.analysis.store;

import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.MethodId;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class InMemoryCodeGraphStore implements CodeGraphStore {

    private final Map<MethodId, CallNode> methods = new LinkedHashMap<>();
    private final Map<MethodId, List<CallEdge>> outgoingEdges = new LinkedHashMap<>();
    private final Map<MethodId, List<CallEdge>> incomingEdges = new LinkedHashMap<>();
    private final Map<RouteKey, List<RouteNode>> routes = new LinkedHashMap<>();

    @Override
    public void saveSnapshot(CodeGraphSnapshot snapshot) {
        Assert.notNull(snapshot, "snapshot must not be null");
        methods.clear();
        outgoingEdges.clear();
        incomingEdges.clear();
        routes.clear();

        for (CallNode node : snapshot.nodes()) {
            methods.put(node.methodId(), node);
        }
        for (CallEdge edge : snapshot.edges()) {
            outgoingEdges.computeIfAbsent(edge.caller(), key -> new ArrayList<>()).add(edge);
            incomingEdges.computeIfAbsent(edge.callee(), key -> new ArrayList<>()).add(edge);
        }
        for (RouteNode route : snapshot.routes()) {
            routes.computeIfAbsent(RouteKey.of(route.repoId(), route.httpMethod(), route.path()),
                    key -> new ArrayList<>()).add(route);
        }
    }

    @Override
    public Optional<CallNode> findMethod(MethodId methodId) {
        return Optional.ofNullable(methods.get(methodId));
    }

    @Override
    public List<CallEdge> findOutgoingEdges(MethodId methodId) {
        return List.copyOf(outgoingEdges.getOrDefault(methodId, List.of()));
    }

    @Override
    public List<CallEdge> findIncomingEdges(MethodId methodId) {
        return List.copyOf(incomingEdges.getOrDefault(methodId, List.of()));
    }

    @Override
    public List<RouteNode> findRoutes(String repoId, String httpMethod, String path) {
        return List.copyOf(routes.getOrDefault(RouteKey.of(repoId, httpMethod, path), List.of()));
    }

    private record RouteKey(String repoId, String httpMethod, String path) {

        private static RouteKey of(String repoId, String httpMethod, String path) {
            return new RouteKey(repoId, normalizeHttpMethod(httpMethod), path);
        }

        private static String normalizeHttpMethod(String httpMethod) {
            if (!StringUtils.hasText(httpMethod)) {
                return "";
            }
            return httpMethod.toUpperCase(Locale.ROOT);
        }
    }
}
