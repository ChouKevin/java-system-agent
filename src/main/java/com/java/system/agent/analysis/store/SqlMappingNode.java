package com.java.system.agent.analysis.store;

import com.java.system.agent.analysis.model.MethodId;

public record SqlMappingNode(
        String repoId,
        MethodId methodId,
        String statementId,
        String sql) {
}
