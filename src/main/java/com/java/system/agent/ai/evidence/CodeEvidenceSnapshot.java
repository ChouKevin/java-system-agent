package com.java.system.agent.ai.evidence;

import com.java.system.agent.ai.tools.ToolNames;
import com.java.system.agent.analysis.model.ApiRouteCandidate;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CodeEvidenceSnapshot(
        EvidenceRequirement requirement,
        EvidenceOutcome outcome,
        String toolName,
        String repoId,
        String apiPath,
        String reasonCode,
        List<ApiRouteCandidate> candidates) {

    public CodeEvidenceSnapshot {
        requirement = Objects.requireNonNull(requirement, "requirement must not be null");
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        toolName = Objects.toString(toolName, "");
        repoId = Objects.toString(repoId, "");
        apiPath = Objects.toString(apiPath, "");
        reasonCode = Objects.toString(reasonCode, "");
        candidates = CollectionUtils.isEmpty(candidates) ? List.of() : List.copyOf(candidates);
    }

    public boolean hasValidEvidence() {
        boolean graphAvailable = outcome == EvidenceOutcome.VERIFIED
                || outcome == EvidenceOutcome.TRANSLATION_UNVERIFIED;
        if (!graphAvailable) {
            return false;
        }
        return requirement != EvidenceRequirement.API_CODE_REQUIRED
                || ToolNames.FIND_API_CALL_GRAPH.equals(toolName);
    }

    public boolean requiresCode() {
        return requirement != EvidenceRequirement.DOCS_ONLY;
    }

    public Map<String, String> traceMetadata() {
        return Map.of(
                "evidenceRequirement", requirement.name(),
                "evidenceOutcome", outcome.name(),
                "evidenceTool", toolName,
                "evidenceReason", reasonCode);
    }
}
