package com.java.system.agent.runtime.port.out;

import java.util.Objects;

/**
 * Capability 執行邊界回傳的已淨化失敗內容
 */
public record CapabilityExecutionFailure(CapabilityExecutionFailureCode code, String description,
                                         String operationSource) {
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    public CapabilityExecutionFailure {
        Objects.requireNonNull(code, "capability execution failure code must not be null");
        Objects.requireNonNull(description, "capability execution failure description must not be null");
        Objects.requireNonNull(operationSource, "capability execution failure operation source must not be null");
        if (description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("capability execution failure description must be nonblank and bounded");
        }
        if (operationSource.isBlank()) {
            throw new IllegalArgumentException("capability execution failure operation source must not be blank");
        }
    }
}
