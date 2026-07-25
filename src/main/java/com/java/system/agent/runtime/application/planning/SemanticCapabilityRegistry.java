package com.java.system.agent.runtime.application.planning;

import com.java.system.agent.runtime.domain.need.InformationNeedType;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 依 information need 型別索引所有可用的 {@link SemanticCapability}
 *
 * <p>由 {@link InformationNeedPlanner#plan} 查詢；{@link #matching} 回傳空集合時，
 * planner 會判定為 {@code CAPABILITY_MISSING} 並讓
 * {@link com.java.system.agent.runtime.application.BoundedAnalysisLoop} 直接終止 run，
 * 而不是退而求其次挑一個不完全匹配的能力去猜——這裡沒有任何 fallback 邏輯</p>
 */
public final class SemanticCapabilityRegistry {

    private final List<SemanticCapability> capabilities;

    public SemanticCapabilityRegistry(Collection<SemanticCapability> capabilities) {
        Objects.requireNonNull(capabilities, "semantic capabilities must not be null");
        this.capabilities = capabilities.stream()
                .map(capability -> Objects.requireNonNull(capability, "semantic capability must not be null"))
                .distinct()
                .sorted()
                .toList();
    }

    public List<SemanticCapability> matching(InformationNeedType needType) {
        Objects.requireNonNull(needType, "information need type must not be null");
        return capabilities.stream()
                .filter(capability -> capability.supports(needType))
                .toList();
    }
}
