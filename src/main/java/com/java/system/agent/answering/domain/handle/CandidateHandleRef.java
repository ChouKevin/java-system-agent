package com.java.system.agent.answering.domain.handle;

import java.util.Objects;

/**
 * 模型在未驗證動作中提交的候選 handle 原始值，answering 必須以本輪 issued 集合依值解析
 */
public record CandidateHandleRef(String value) {

    public CandidateHandleRef {
        Objects.requireNonNull(value, "candidate handle reference value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("candidate handle reference value must not be blank");
        }
    }
}
