package com.java.system.agent.runtime.domain.answer;

import java.util.List;
import java.util.Objects;

/**
 * 一份回答文件的整體接受與逐段判定結果
 */
public record AnswerVerdict(AnswerDisposition disposition, List<StatementVerdict> statementVerdicts,
        List<String> unaddressedParts, List<String> blockingUncertainties, List<String> rejectionReasons) {
    public AnswerVerdict {
        Objects.requireNonNull(disposition, "answer verdict disposition must not be null");
        statementVerdicts = immutableList(statementVerdicts, "answer verdict statements");
        unaddressedParts = immutableList(unaddressedParts, "unaddressed parts");
        blockingUncertainties = immutableList(blockingUncertainties, "blocking uncertainties");
        rejectionReasons = immutableList(rejectionReasons, "rejection reasons");
    }

    private static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        for (T value : values) {
            Objects.requireNonNull(value, name + " must not contain null elements");
            if (value instanceof String text && text.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank text");
            }
        }
        return List.copyOf(values);
    }
}
