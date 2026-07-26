package com.java.system.agent.runtime.domain.capability;

/**
 * Capability 查詢引數不符合對外契約時的可辨識例外
 */
public final class CapabilityQueryContractException extends IllegalArgumentException {

    public CapabilityQueryContractException(String message) {
        super(message);
    }

    public CapabilityQueryContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
