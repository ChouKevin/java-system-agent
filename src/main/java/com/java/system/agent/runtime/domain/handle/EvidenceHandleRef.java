package com.java.system.agent.runtime.domain.handle;

import java.util.Objects;

/**
 * 模型在回答文件中提交的證據 handle 原始值，runtime 必須以本輪 issued 集合依值解析
 */
public record EvidenceHandleRef(String value) {

    public EvidenceHandleRef {
        Objects.requireNonNull(value, "evidence handle reference value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("evidence handle reference value must not be blank");
        }
    }
}
