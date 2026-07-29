package com.java.system.agent.codeintelligence.planning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * outgoing-call-graph executor 的 capability 專屬輸入
 */
public record OutgoingCallGraphExecutionInput(@Min(1) @Max(2) int depth) {
}
