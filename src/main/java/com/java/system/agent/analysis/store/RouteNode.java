package com.java.system.agent.analysis.store;

import com.java.system.agent.analysis.model.MethodId;

public record RouteNode(
        String repoId,
        String httpMethod,
        String path,
        MethodId handler) {
}
