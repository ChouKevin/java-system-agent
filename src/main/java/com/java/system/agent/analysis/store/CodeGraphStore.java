package com.java.system.agent.analysis.store;

import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.CallNode;
import com.java.system.agent.analysis.model.MethodId;

import java.util.List;
import java.util.Optional;

public interface CodeGraphStore {

    void saveSnapshot(CodeGraphSnapshot snapshot);

    Optional<CallNode> findMethod(MethodId methodId);

    List<CallEdge> findOutgoingEdges(MethodId methodId);

    List<CallEdge> findIncomingEdges(MethodId methodId);

    List<RouteNode> findRoutes(String repoId, String httpMethod, String path);
}
