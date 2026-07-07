package com.java.system.agent.analysis.callgraph;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("方法呼叫類型分類")
public enum CallType {
    // 核心業務層
    @JsonPropertyDescription("專案內 Service 業務邏輯")
    INTERNAL_SERVICE(null),

    @JsonPropertyDescription("專案內 Controller 入口")
    INTERNAL_CONTROLLER(null),

    @JsonPropertyDescription("專案內 Component 組件")
    INTERNAL_COMPONENT(null),

    @JsonPropertyDescription("排程任務")
    INTERNAL_SCHEDULE(null),

    @JsonPropertyDescription("介面預設實作方法")
    INTERNAL_INTERFACE_DEFAULT(null),

    // 資料與外部交互層
    @JsonPropertyDescription("資料庫存取 (Mapper/Repository)")
    DATA_ACCESS("Database layer"),

    @JsonPropertyDescription("遠端微服務呼叫 (RPC/Feign)")
    RPC_CLIENT(null),

    @JsonPropertyDescription("訊息隊列操作 (MQ)")
    MESSAGE_QUEUE(null),

    @JsonPropertyDescription("快取操作 (Redis/Cache)")
    CACHE_OP(null),

    // 遍歷控制
    @JsonPropertyDescription("循環回邊 — 目標方法已存在於目前 DFS path")
    CYCLE_BACK_EDGE("Cycle back edge — target already exists in current DFS path"),

    @JsonPropertyDescription("遍歷深度截斷 — 超過設定上限，callees 列出被截斷的下層方法 signature")
    TRAVERSAL_CUTOFF("Traversal cutoff — expand via callees to see deeper logic"),

    // 基礎設施層
    @JsonPropertyDescription("一般物件或工具類")
    INTERNAL_CLASS(null),

    @JsonPropertyDescription("無法解析的呼叫 — 型別推斷失敗或原始碼解析失敗")
    UNRESOLVED("Unresolved type"),

    @JsonPropertyDescription("程式碼生成方法 (Lombok @Data/@Builder 等)")
    GENERATED_CODE("Lombok generated method"),

    @JsonPropertyDescription("第三方函式庫 (JDK / 外部 dependency)")
    EXTERNAL_LIB("External library (source not found)"),

    @JsonPropertyDescription("介面定義")
    INTERFACE(null);

    private final String defaultDesc;

    CallType(String defaultDesc) {
        this.defaultDesc = defaultDesc;
    }

    /** 取得此類型的預設描述，leaf node 未指定 desc 時使用 */
    public String getDefaultDesc() {
        return defaultDesc;
    }
}
