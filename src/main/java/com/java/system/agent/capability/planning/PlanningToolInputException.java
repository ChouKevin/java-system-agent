package com.java.system.agent.capability.planning;

/**
 * 規劃工具原始輸入違反 JSON 或型別契約時使用的可拒絕例外
 */
public final class PlanningToolInputException extends RuntimeException {

    public PlanningToolInputException() {
        super("invalid planning tool input");
    }

    public PlanningToolInputException(Throwable cause) {
        super("invalid planning tool input", cause);
    }
}
