package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.ClaimVerdict;
import com.java.system.agent.runtime.port.out.AnswerDraft;
import com.java.system.agent.runtime.port.out.ClaimVerificationPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * {@link ClaimVerificationPort} 的測試替身，依註冊順序依序回傳判定並記錄每次呼叫的引數
 *
 * <p>判定序列耗盡後固定回傳最後一筆，讓呼叫端不需要為每一次可能的呼叫都預先註冊</p>
 */
public final class FakeClaimVerificationAdapter implements ClaimVerificationPort {

    private final Deque<List<ClaimVerdict>> scriptedVerdicts;
    private final List<Invocation> invocations = new ArrayList<>();

    @SafeVarargs
    public FakeClaimVerificationAdapter(List<ClaimVerdict>... scriptedVerdicts) {
        Objects.requireNonNull(scriptedVerdicts, "scripted claim verdicts must not be null");
        if (scriptedVerdicts.length == 0) {
            throw new IllegalArgumentException("at least one scripted claim verdict list is required");
        }
        this.scriptedVerdicts = new ArrayDeque<>(List.of(scriptedVerdicts));
    }

    @Override
    public synchronized List<ClaimVerdict> verify(AnswerDraft draft, List<CitableEvidence> citableEvidence) {
        Objects.requireNonNull(draft, "answer draft must not be null");
        Objects.requireNonNull(citableEvidence, "citable evidence must not be null");
        invocations.add(new Invocation(draft, citableEvidence));
        if (scriptedVerdicts.size() > 1) {
            return scriptedVerdicts.removeFirst();
        }
        return scriptedVerdicts.peekFirst();
    }

    public synchronized List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public record Invocation(AnswerDraft draft, List<CitableEvidence> citableEvidence) {

        public Invocation {
            Objects.requireNonNull(draft, "answer draft must not be null");
            Objects.requireNonNull(citableEvidence, "citable evidence must not be null");
            citableEvidence = List.copyOf(citableEvidence);
        }
    }
}
