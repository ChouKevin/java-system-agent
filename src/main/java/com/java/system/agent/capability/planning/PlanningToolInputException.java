package com.java.system.agent.capability.planning;

import java.util.Objects;
import java.util.Optional;

/**
 * 規劃工具原始輸入違反 JSON 或型別契約時使用的可拒絕例外
 */
public final class PlanningToolInputException extends RuntimeException {

    private final Optional<String> safeDiagnostic;

    public PlanningToolInputException() {
        this(Optional.empty(), null);
    }

    public PlanningToolInputException(Throwable cause) {
        this(Optional.empty(), cause);
    }

    PlanningToolInputException(String safeDiagnostic, Throwable cause) {
        this(Optional.of(Objects.requireNonNull(safeDiagnostic, "safe diagnostic must not be null")), cause);
    }

    private PlanningToolInputException(Optional<String> safeDiagnostic, Throwable cause) {
        super("invalid planning tool input", cause);
        this.safeDiagnostic = Objects.requireNonNull(safeDiagnostic, "safe diagnostic must not be null");
    }

    /**
     * 回傳可提供給模型的限量結構資訊，不包含原始輸入或欄位值
     */
    public Optional<String> safeDiagnostic() {
        return safeDiagnostic;
    }
}
