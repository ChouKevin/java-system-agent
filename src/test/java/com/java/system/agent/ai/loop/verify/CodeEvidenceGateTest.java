package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.evidence.CodeEvidenceTracker;
import com.java.system.agent.ai.evidence.EvidenceRequirement;
import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeEvidenceGateTest {

    @Test
    void should_revise_when_business_question_has_not_attempted_code_analysis() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        CodeEvidenceGate gate = new CodeEvidenceGate(tracker);

        Verdict verdict = gate.verify(
                new Candidate("系統會依會員等級計算"), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("find_call_graph");
    }

    @Test
    void should_revise_with_api_tool_when_api_question_has_not_attempted_code_analysis() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);

        Verdict verdict = new CodeEvidenceGate(tracker).verify(
                new Candidate("這個 API 會回傳成功"), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("find_api_call_graph");
    }

    @Test
    void should_accept_when_verified_code_evidence_exists() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        tracker.recordVerified("find_call_graph", "bonus-service", "", List.of());

        Verdict verdict = new CodeEvidenceGate(tracker).verify(
                new Candidate("系統依會員等級與活動條件計算"), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_when_requirement_is_docs_only() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(EvidenceRequirement.DOCS_ONLY);

        Verdict verdict = new CodeEvidenceGate(tracker).verify(
                new Candidate("文件中的說明"), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_require_caveat_when_translation_is_unverified() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        tracker.recordTranslationUnverified(
                "find_call_graph", "bonus-service", "", List.of());

        Verdict verdict = new CodeEvidenceGate(tracker).verify(
                new Candidate("系統依會員等級計算"), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("尚未通過完整驗證");
    }

    @Test
    void should_accept_when_translation_unverified_answer_has_required_caveat() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.BUSINESS_CODE_REQUIRED);
        tracker.recordTranslationUnverified(
                "find_call_graph", "bonus-service", "", List.of());

        Verdict verdict = new CodeEvidenceGate(tracker).verify(
                new Candidate("此結論尚未通過完整驗證"), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_only_exact_restricted_answer_when_evidence_is_not_found() {
        CodeEvidenceTracker tracker = new CodeEvidenceTracker(
                EvidenceRequirement.API_CODE_REQUIRED);
        tracker.recordNotFound(
                "find_api_call_graph", "/api/orders", "NOT_FOUND", List.of());
        CodeEvidenceGate gate = new CodeEvidenceGate(tracker);
        String restrictedAnswer = "找不到與指定條件完全相符的 API，因此無法從 codebase 驗證。"
                + "請確認 HTTP method、API path 或 repo。";

        Verdict rejected = gate.verify(new Candidate("猜測的 API 結論"), state());
        Verdict accepted = gate.verify(new Candidate(restrictedAnswer), state());

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.critique()).contains(restrictedAnswer);
        assertThat(accepted.accepted()).isTrue();
    }

    private LoopState state() {
        return LoopState.init(new LoopRequest("thread", "問題"));
    }
}
