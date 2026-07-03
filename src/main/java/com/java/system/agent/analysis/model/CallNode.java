package com.java.system.agent.analysis.model;

import com.java.system.agent.analysis.callgraph.CallType;

import java.util.Map;

public record CallNode(
        MethodId methodId,
        String signature,
        CallType callType,
        String sourceFile,
        Integer startLine,
        Integer endLine,
        Map<String, String> annotations,
        String code) {
}
