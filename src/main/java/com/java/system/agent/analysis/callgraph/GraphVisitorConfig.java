package com.java.system.agent.analysis.callgraph;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Set;

@Getter
@Builder
public class GraphVisitorConfig {

    // 保留的節點類型
    @Singular
    private Set<CallType> keepTypes;

    // 穿透的節點類型（子節點提升至父層）
    @Singular
    private Set<CallType> passThroughTypes;

    // 附帶完整源碼的節點類型
    @Singular
    private Set<CallType> codeInclusionTypes;

    // 保留 interface default methods
    @Builder.Default
    private boolean keepInterfaceDefaultMethods = true;

    /** 預設策略：只保留核心業務邏輯 */
    public static GraphVisitorConfig defaultConfig() {
        return GraphVisitorConfig.builder()
                .keepType(CallType.INTERNAL_CONTROLLER)
                .keepType(CallType.INTERNAL_SERVICE)
                .keepType(CallType.INTERNAL_COMPONENT)
                .keepType(CallType.INTERNAL_CLASS)
                .keepType(CallType.INTERNAL_SCHEDULE)
                .keepType(CallType.INTERNAL_INTERFACE_DEFAULT)
                .keepType(CallType.CYCLE_BACK_EDGE)
                .keepType(CallType.TRAVERSAL_CUTOFF)
                .keepType(CallType.RPC_CLIENT)
                .keepType(CallType.DATA_ACCESS)
                .keepType(CallType.MESSAGE_QUEUE)
                .passThroughType(CallType.INTERFACE)
                .codeInclusionType(CallType.INTERNAL_CONTROLLER)
                .codeInclusionType(CallType.INTERNAL_SERVICE)
                .codeInclusionType(CallType.INTERNAL_COMPONENT)
                .codeInclusionType(CallType.INTERNAL_CLASS)
                .codeInclusionType(CallType.DATA_ACCESS)
                .codeInclusionType(CallType.INTERNAL_SCHEDULE)
                .codeInclusionType(CallType.INTERNAL_INTERFACE_DEFAULT)
                .codeInclusionType(CallType.TRAVERSAL_CUTOFF)
                .codeInclusionType(CallType.MESSAGE_QUEUE)
                .build();
    }

    /** 資料流策略：只關注資料存取與外部交互 */
    public static GraphVisitorConfig dataFlowConfig() {
        return GraphVisitorConfig.builder()
                .keepType(CallType.INTERNAL_SERVICE)
                .keepType(CallType.DATA_ACCESS)
                .keepType(CallType.RPC_CLIENT)
                .keepType(CallType.MESSAGE_QUEUE)
                .keepType(CallType.CACHE_OP)
                .passThroughType(CallType.INTERFACE)
                .passThroughType(CallType.INTERNAL_CONTROLLER)
                .passThroughType(CallType.INTERNAL_COMPONENT)
                .codeInclusionType(CallType.INTERNAL_SERVICE)
                .codeInclusionType(CallType.DATA_ACCESS)
                .codeInclusionType(CallType.MESSAGE_QUEUE)
                .codeInclusionType(CallType.RPC_CLIENT)
                .build();
    }

    /** 是否應包含源碼 */
    public boolean shouldIncludeCode(CallType type) {
        return codeInclusionTypes != null && codeInclusionTypes.contains(type);
    }
}
