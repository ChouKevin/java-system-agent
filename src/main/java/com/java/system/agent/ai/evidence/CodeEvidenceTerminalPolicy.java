package com.java.system.agent.ai.evidence;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.TerminalAnswerPolicy;

import java.util.Objects;

public final class CodeEvidenceTerminalPolicy implements TerminalAnswerPolicy {

    private static final String TRANSLATION_CAVEAT_MARKER = "尚未通過完整驗證";
    private static final String TRANSLATION_CAVEAT = "⚠️ 以上結論尚未通過完整驗證。";

    private final CodeEvidenceTracker tracker;
    private final EvidenceFallbackRenderer fallbackRenderer = new EvidenceFallbackRenderer();

    public CodeEvidenceTerminalPolicy(CodeEvidenceTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Candidate apply(Candidate candidate) {
        CodeEvidenceSnapshot snapshot = tracker.snapshot();
        if (!snapshot.requiresCode()) {
            return candidate;
        }
        if (snapshot.hasValidEvidence()) {
            return snapshot.outcome() == EvidenceOutcome.TRANSLATION_UNVERIFIED
                    ? withTranslationCaveat(candidate)
                    : candidate;
        }
        return new Candidate(fallbackRenderer.render(snapshot));
    }

    private Candidate withTranslationCaveat(Candidate candidate) {
        String answer = Objects.toString(candidate.answer(), "");
        if (answer.contains(TRANSLATION_CAVEAT_MARKER)) {
            return candidate;
        }
        return new Candidate(answer + System.lineSeparator() + System.lineSeparator()
                + TRANSLATION_CAVEAT);
    }
}
