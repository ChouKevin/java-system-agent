package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.model.ApiRouteCandidate;

public record ApiRouteSummary(String repoId, String httpMethod, String routeTemplate) {

    public static ApiRouteSummary from(ApiRouteCandidate candidate) {
        return new ApiRouteSummary(
                candidate.repoId(), candidate.httpMethod(), candidate.routeTemplate());
    }
}
