package com.java.system.agent.ai.evidence;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.analysis.model.ApiRouteCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceTerminalPolicyTest {

    @Test
    void should_replace_business_claim_when_forced_finalization_has_no_evidence() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        CodeEvidenceTerminalPolicy policy = new CodeEvidenceTerminalPolicy(tracker);

        Candidate result = policy.apply(new Candidate("系統一定會發送通知"));

        assertThat(result.answer()).contains("無法從 codebase 驗證");
        assertThat(result.answer()).doesNotContain("一定會發送通知");
    }

    @Test
    void should_preserve_candidate_when_requirement_is_docs_only() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(EvidenceRequirement.DOCS_ONLY);
        Candidate candidate = new Candidate("文件結論");

        Candidate result = new CodeEvidenceTerminalPolicy(tracker).apply(candidate);

        assertThat(result).isSameAs(candidate);
    }

    @Test
    void should_preserve_candidate_when_valid_evidence_exists() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        tracker.recordVerified("find_call_graph", "bonus-service", "", List.of());
        Candidate candidate = new Candidate("已驗證結論");

        Candidate result = new CodeEvidenceTerminalPolicy(tracker).apply(candidate);

        assertThat(result).isSameAs(candidate);
    }

    @Test
    void should_add_caveat_when_translation_evidence_is_unverified() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        tracker.recordTranslationUnverified(
                "find_call_graph", "bonus-service", "", List.of());

        Candidate result = new CodeEvidenceTerminalPolicy(tracker).apply(
                new Candidate("翻譯後的業務結論"));

        assertThat(result.answer())
                .contains("翻譯後的業務結論")
                .contains("尚未通過完整驗證");
    }

    @Test
    void should_render_only_sanitized_route_fields_when_evidence_is_ambiguous() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);
        tracker.recordAmbiguous("find_api_call_graph", "/api/orders", List.of(
                new ApiRouteCandidate(
                        "bonus\n-service",
                        "GET<script>",
                        "/api/{id}\nsecret",
                        "internal.secret.package",
                        "SecretController",
                        "leakImplementation")));

        Candidate result = new CodeEvidenceTerminalPolicy(tracker).apply(
                new Candidate("不應保留的結論"));

        assertThat(result.answer()).contains(
                "- GET?script? /api/{id}?secret（repo: bonus?-service）");
        assertThat(result.answer()).doesNotContain(
                "internal.secret.package", "SecretController", "leakImplementation", "<script>");
    }
}
