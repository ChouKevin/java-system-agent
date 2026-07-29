package com.java.system.agent.codebase.planning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * incoming-call-graph executor 的 capability 專屬輸入
 */
public record IncomingCallGraphExecutionInput(@Min(1) @Max(2) int depth) {
}
