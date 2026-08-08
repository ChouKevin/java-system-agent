package com.java.system.agent.codeintelligence.planning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** 事件監聽器探索 capability 的 execution input */
public record DiscoverEventListenersExecutionInput(
        @NotBlank String eventType, @Min(0) int offset, @Min(1) @Max(100) int limit) {
}
