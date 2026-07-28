package com.java.system.agent.inbox.port.in;

/**
 * 已認領工作無法確認已安全復原時通知 worker 停止新的 claim
 */
public final class ClaimRecoveryFailureException extends RuntimeException {

    public ClaimRecoveryFailureException(Throwable processingFailure) {
        super("claimed work could not be recovered", processingFailure);
    }

    public ClaimRecoveryFailureException(Throwable processingFailure, Throwable recoveryFailure) {
        super("claimed work could not be recovered", processingFailure);
        addSuppressed(recoveryFailure);
    }
}
