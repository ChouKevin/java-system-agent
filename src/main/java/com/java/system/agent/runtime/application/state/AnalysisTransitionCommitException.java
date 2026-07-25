package com.java.system.agent.runtime.application.state;

/**
 * 事件無法成功落地、或落地後回傳的狀態與候選狀態不一致時，由
 * {@link TransitionCommitter#apply} 拋出
 */
public final class AnalysisTransitionCommitException extends RuntimeException {

    public AnalysisTransitionCommitException(String message) {
        super(message);
    }

    public AnalysisTransitionCommitException(String message, Throwable cause) {
        super(message, cause);
    }
}
