package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.answer.CitableEvidence;
import com.java.system.agent.runtime.domain.answer.VerifiedClaim;
import com.java.system.agent.runtime.domain.run.AnalysisWarning;
import com.java.system.agent.runtime.domain.scope.RepositoryId;
import com.java.system.agent.runtime.port.out.AnswerCompositionPort;
import com.java.system.agent.runtime.port.out.AnswerDraft;
import com.java.system.agent.runtime.port.out.RepositoryDescriptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * {@link AnswerCompositionPort} 的測試替身，依註冊順序依序回傳草稿並記錄每次呼叫的引數
 *
 * <p>草稿序列耗盡後固定回傳最後一筆，讓呼叫端不需要為每一次可能的呼叫都預先註冊；
 * {@code compose} 與 {@link #composeClarification} 各自記錄自己的呼叫，讓測試能斷言
 * 究竟是哪一個被呼叫</p>
 */
public final class FakeAnswerCompositionAdapter implements AnswerCompositionPort {

    private final Deque<AnswerDraft> scriptedDrafts;
    private final List<Invocation> invocations = new ArrayList<>();
    private final Deque<AnswerDraft> scriptedClarifications = new ArrayDeque<>();
    private final List<ClarificationInvocation> clarificationInvocations = new ArrayList<>();

    public FakeAnswerCompositionAdapter(AnswerDraft... scriptedDrafts) {
        Objects.requireNonNull(scriptedDrafts, "scripted answer drafts must not be null");
        if (scriptedDrafts.length == 0) {
            throw new IllegalArgumentException("at least one scripted answer draft is required");
        }
        this.scriptedDrafts = new ArrayDeque<>(List.of(scriptedDrafts));
    }

    @Override
    public synchronized AnswerDraft compose(
            String question,
            List<CitableEvidence> citableEvidence,
            List<AnalysisWarning> warnings,
            List<VerifiedClaim> rejectedClaims) {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(citableEvidence, "citable evidence must not be null");
        Objects.requireNonNull(warnings, "analysis warnings must not be null");
        Objects.requireNonNull(rejectedClaims, "rejected claims must not be null");
        invocations.add(new Invocation(question, citableEvidence, warnings, rejectedClaims));
        if (scriptedDrafts.size() > 1) {
            return scriptedDrafts.removeFirst();
        }
        return scriptedDrafts.peekFirst();
    }

    @Override
    public synchronized AnswerDraft composeClarification(
            String question,
            List<RepositoryDescriptor> catalog,
            List<RepositoryId> rejectedCandidates) {
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(catalog, "repository catalog must not be null");
        Objects.requireNonNull(rejectedCandidates, "rejected candidates must not be null");
        if (scriptedClarifications.isEmpty()) {
            throw new IllegalStateException("no scripted clarification draft was registered");
        }
        clarificationInvocations.add(new ClarificationInvocation(question, catalog, rejectedCandidates));
        if (scriptedClarifications.size() > 1) {
            return scriptedClarifications.removeFirst();
        }
        return scriptedClarifications.peekFirst();
    }

    public synchronized void scriptClarification(AnswerDraft draft) {
        scriptedClarifications.add(Objects.requireNonNull(draft, "scripted clarification draft must not be null"));
    }

    public synchronized List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public synchronized List<ClarificationInvocation> clarificationInvocations() {
        return List.copyOf(clarificationInvocations);
    }

    public record Invocation(
            String question,
            List<CitableEvidence> citableEvidence,
            List<AnalysisWarning> warnings,
            List<VerifiedClaim> rejectedClaims) {

        public Invocation {
            Objects.requireNonNull(question, "question must not be null");
            Objects.requireNonNull(citableEvidence, "citable evidence must not be null");
            Objects.requireNonNull(warnings, "analysis warnings must not be null");
            Objects.requireNonNull(rejectedClaims, "rejected claims must not be null");
            citableEvidence = List.copyOf(citableEvidence);
            warnings = List.copyOf(warnings);
            rejectedClaims = List.copyOf(rejectedClaims);
        }
    }

    public record ClarificationInvocation(
            String question,
            List<RepositoryDescriptor> catalog,
            List<RepositoryId> rejectedCandidates) {

        public ClarificationInvocation {
            Objects.requireNonNull(question, "question must not be null");
            Objects.requireNonNull(catalog, "repository catalog must not be null");
            Objects.requireNonNull(rejectedCandidates, "rejected candidates must not be null");
            catalog = List.copyOf(catalog);
            rejectedCandidates = List.copyOf(rejectedCandidates);
        }
    }
}
