package com.java.system.agent.runtime.port.out;

/**
 * Capability executor 違反 runtime 合約時拋出的例外
 */
public final class CapabilityExecutionContractException extends IllegalStateException {
    public CapabilityExecutionContractException(String message) {
        super(message);
    }

    public CapabilityExecutionContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
