package com.java.system.agent.answering.port.out;

/**
 * durable cancellation marker 在 terminal acceptance 前取得仲裁勝利
 */
public final class TerminalAcceptanceCancelledException extends IllegalStateException {

    public TerminalAcceptanceCancelledException(String message) {
        super(message);
    }
}
