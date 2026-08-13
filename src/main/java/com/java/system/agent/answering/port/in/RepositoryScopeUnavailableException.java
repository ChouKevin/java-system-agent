package com.java.system.agent.answering.port.in;

/**
 * 已設定 repository scope 的 revision 暫時無法取得，inbox 應依一般 infrastructure retry 規則處理
 */
public final class RepositoryScopeUnavailableException extends AnswerExecutionUnavailableException {

    public RepositoryScopeUnavailableException() {
        super("configured repository scope is temporarily unavailable");
    }
}
