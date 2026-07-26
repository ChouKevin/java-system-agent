package com.java.system.agent.runtime.adapter.fake;

import com.java.system.agent.runtime.domain.answer.AnswerVerdict;
import com.java.system.agent.runtime.port.out.AnswerVerificationContext;
import com.java.system.agent.runtime.port.out.AnswerVerificationPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 依插入順序回傳腳本化回答判定並保留驗證 context 的測試替身
 */
public final class FakeAnswerVerificationAdapter implements AnswerVerificationPort {

    private final Deque<AnswerVerdict> scriptedVerdicts;
    private final List<AnswerVerificationContext> contexts = new ArrayList<>();

    public FakeAnswerVerificationAdapter(AnswerVerdict... scriptedVerdicts) {
        Objects.requireNonNull(scriptedVerdicts, "scripted answer verdicts must not be null");
        if (scriptedVerdicts.length == 0) {
            throw new IllegalArgumentException("at least one scripted answer verdict is required");
        }
        this.scriptedVerdicts = new ArrayDeque<>(List.of(scriptedVerdicts));
    }

    @Override
    public synchronized AnswerVerdict verify(AnswerVerificationContext context) {
        contexts.add(Objects.requireNonNull(context, "answer verification context must not be null"));
        if (scriptedVerdicts.isEmpty()) {
            throw new IllegalStateException("no scripted answer verdict remains");
        }
        return scriptedVerdicts.removeFirst();
    }

    public synchronized List<AnswerVerificationContext> contexts() {
        return List.copyOf(contexts);
    }
}
