package com.java.system.agent.answering.port.out;

/**
 * Capability executor 違反 answering 合約時拋出的例外
 */
public final class CapabilityExecutionContractException extends IllegalStateException {
    public CapabilityExecutionContractException(String message) {
        super(message);
    }

    public CapabilityExecutionContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
