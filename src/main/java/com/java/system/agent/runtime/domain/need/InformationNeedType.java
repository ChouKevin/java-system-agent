package com.java.system.agent.runtime.domain.need;

/**
 * {@link InformationNeed} 所描述的資訊類別
 *
 * <p>{@code SemanticCapabilityRegistry} 依此類別找出能滿足它的 {@code SemanticCapability}</p>
 */
public enum InformationNeedType {
    ENTRY_POINT,
    METHOD_IMPLEMENTATION,
    CROSS_SERVICE_TARGET,
    TRANSACTION_BOUNDARY,
    DATA_ACCESS,
    KNOWLEDGE_CONTEXT
}
