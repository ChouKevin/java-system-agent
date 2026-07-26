package com.java.system.agent.runtime.domain.run;

import java.util.Objects;

/**
 * Agent Run 持久化擁有的不可變 session 與問題識別
 */
public record RunRequestIdentity(String sessionIdValue, String exactQuestion) {

    public RunRequestIdentity {
        Objects.requireNonNull(sessionIdValue, "run session ID value must not be null");
        Objects.requireNonNull(exactQuestion, "run exact question must not be null");
        if (sessionIdValue.isBlank() || exactQuestion.isBlank()) {
            throw new IllegalArgumentException("run request identity values must not be blank");
        }
    }
}
