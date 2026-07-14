package com.java.system.agent.ai.tools;

import java.util.List;

public record ApiAnalysisToolResult(
        ApiAnalysisStatus status,
        boolean verified,
        String answer,
        String reasonCode,
        List<ApiRouteSummary> candidates) {

    public ApiAnalysisToolResult {
        candidates = List.copyOf(candidates);
    }
}
