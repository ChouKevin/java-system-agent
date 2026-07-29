package com.java.system.agent.model.verification;

import com.java.system.agent.answering.domain.answer.AnswerVerificationMode;
import com.java.system.agent.answering.port.out.AnswerVerificationContext;
import com.java.system.agent.answering.port.out.AnswerVerificationPort;
import com.java.system.agent.answering.port.out.AnswerVerificationResult;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 依 AnswerProposed 持久化 mode 選出唯一 verifier strategy 的 port adapter
 */
public final class AnswerVerificationDispatcher implements AnswerVerificationPort {

    private final Map<AnswerVerificationMode, AnswerVerificationStrategy> strategies;

    public AnswerVerificationDispatcher(AnswerVerificationStrategy... strategies) {
        Objects.requireNonNull(strategies, "answer verification strategies must not be null");
        if (strategies.length != AnswerVerificationMode.values().length) {
            throw new IllegalArgumentException("answer verification strategies must contain each mode exactly once");
        }
        Map<AnswerVerificationMode, AnswerVerificationStrategy> selected = new EnumMap<>(AnswerVerificationMode.class);
        for (AnswerVerificationStrategy strategy : strategies) {
            AnswerVerificationStrategy requiredStrategy = Objects.requireNonNull(strategy,
                    "answer verification strategy must not be null");
            if (Objects.nonNull(selected.put(requiredStrategy.mode(), requiredStrategy))) {
                throw new IllegalArgumentException("duplicate answer verification strategy mode");
            }
        }
        if (!selected.keySet().containsAll(java.util.List.of(AnswerVerificationMode.values()))) {
            throw new IllegalArgumentException("answer verification strategy mode is missing");
        }
        this.strategies = Map.copyOf(selected);
    }

    @Override
    public AnswerVerificationResult verify(AnswerVerificationMode mode, AnswerVerificationContext context) {
        Objects.requireNonNull(mode, "answer verification mode must not be null");
        Objects.requireNonNull(context, "answer verification context must not be null");
        AnswerVerificationStrategy strategy = strategies.get(mode);
        if (Objects.isNull(strategy)) {
            throw new IllegalStateException("answer verification strategy mode is missing");
        }
        return strategy.verify(mode, context);
    }
}
