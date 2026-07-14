package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.evidence.CodeEvidenceSnapshot;
import com.java.system.agent.ai.evidence.CodeEvidenceTracker;
import com.java.system.agent.ai.evidence.EvidenceFallbackRenderer;
import com.java.system.agent.ai.evidence.EvidenceOutcome;
import com.java.system.agent.ai.evidence.EvidenceRequirement;
import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;

import java.util.Objects;

public final class CodeEvidenceGate implements VerifyGate {

    private static final String TRANSLATION_CAVEAT = "尚未通過完整驗證";

    private final CodeEvidenceTracker tracker;
    private final EvidenceFallbackRenderer fallbackRenderer = new EvidenceFallbackRenderer();

    public CodeEvidenceGate(CodeEvidenceTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        CodeEvidenceSnapshot snapshot = tracker.snapshot();
        if (snapshot.requirement() == EvidenceRequirement.DOCS_ONLY) {
            return Verdict.accept();
        }
        if (snapshot.hasValidEvidence()) {
            if (snapshot.outcome() == EvidenceOutcome.TRANSLATION_UNVERIFIED
                    && !Objects.toString(candidate.answer(), "").contains(TRANSLATION_CAVEAT)) {
                return Verdict.revise("請明確註明這部分結論尚未通過完整驗證");
            }
            return Verdict.accept();
        }
        if (snapshot.outcome() == EvidenceOutcome.NOT_ATTEMPTED) {
            String requiredTool = snapshot.requirement() == EvidenceRequirement.API_CODE_REQUIRED
                    ? "find_api_call_graph"
                    : "find_call_graph";
            return Verdict.revise("回答前必須呼叫 " + requiredTool + " 取得 code evidence");
        }

        String restrictedAnswer = fallbackRenderer.render(snapshot);
        if (Objects.toString(candidate.answer(), "").strip().equals(restrictedAnswer)) {
            return Verdict.accept();
        }
        return Verdict.revise("缺少有效 code evidence；請只回覆以下內容：\n" + restrictedAnswer);
    }
}
