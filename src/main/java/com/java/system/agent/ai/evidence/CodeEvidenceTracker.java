package com.java.system.agent.ai.evidence;

import com.java.system.agent.analysis.model.ApiRouteCandidate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class CodeEvidenceTracker {

    public static final String CONTEXT_KEY = "codeEvidenceTracker";

    private final AtomicReference<CodeEvidenceSnapshot> state;

    public CodeEvidenceTracker(EvidenceRequirement requirement) {
        state = new AtomicReference<>(new CodeEvidenceSnapshot(
                requirement,
                EvidenceOutcome.NOT_ATTEMPTED,
                "",
                "",
                "",
                "",
                List.of()));
    }

    public CodeEvidenceSnapshot snapshot() {
        return state.get();
    }

    public void recordVerified(
            String toolName, String repoId, String apiPath, List<ApiRouteCandidate> candidates) {
        record(EvidenceOutcome.VERIFIED, toolName, repoId, apiPath, "", candidates);
    }

    public void recordTranslationUnverified(
            String toolName, String repoId, String apiPath, List<ApiRouteCandidate> candidates) {
        record(EvidenceOutcome.TRANSLATION_UNVERIFIED,
                toolName, repoId, apiPath, "TRANSLATION_UNVERIFIED", candidates);
    }

    public void recordNotFound(
            String toolName, String apiPath, String reasonCode, List<ApiRouteCandidate> candidates) {
        record(EvidenceOutcome.NOT_FOUND, toolName, "", apiPath, reasonCode, candidates);
    }

    public void recordAmbiguous(
            String toolName, String apiPath, List<ApiRouteCandidate> candidates) {
        record(EvidenceOutcome.AMBIGUOUS,
                toolName, "", apiPath, "MULTIPLE_API_CANDIDATES", candidates);
    }

    public void recordFailed(
            String toolName, String repoId, String apiPath, String reasonCode) {
        record(EvidenceOutcome.ANALYSIS_FAILED,
                toolName, repoId, apiPath, reasonCode, List.of());
    }

    private void record(
            EvidenceOutcome outcome,
            String toolName,
            String repoId,
            String apiPath,
            String reasonCode,
            List<ApiRouteCandidate> candidates) {
        state.updateAndGet(current -> {
            CodeEvidenceSnapshot next = new CodeEvidenceSnapshot(
                    current.requirement(),
                    outcome,
                    toolName,
                    repoId,
                    apiPath,
                    reasonCode,
                    candidates);
            return shouldPreserve(current, next) ? current : next;
        });
    }

    private boolean shouldPreserve(
            CodeEvidenceSnapshot current, CodeEvidenceSnapshot next) {
        if (!current.hasValidEvidence()) {
            return false;
        }
        return current.outcome() == EvidenceOutcome.VERIFIED
                || !next.hasValidEvidence();
    }
}
