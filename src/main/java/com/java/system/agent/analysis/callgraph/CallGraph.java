package com.java.system.agent.analysis.callgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonClassDescription("方法呼叫鏈結圖")
public class CallGraph {

    @JsonPropertyDescription("唯一簽章 (e.g. com.service.VipService.upgrade(String))")
    private String signature;

    @JsonPropertyDescription("類別名稱")
    private String className;

    @JsonPropertyDescription("套件名稱 (dot notation, e.g. 'com.java.system.agent.service')")
    private String packagePath;

    @JsonPropertyDescription("方法名稱")
    private String methodName;

    @JsonPropertyDescription("方法類型")
    private CallType callType;

    @JsonPropertyDescription("方法描述 (Javadoc / Profile / 分析摘要)")
    private String desc;

    @JsonPropertyDescription("程式碼內容 (通常只在根節點或關鍵邏輯節點提供)，由LLM決定是否深入查詢")
    private String code;

    @JsonPropertyDescription("方法上的 Annotation (Key: 名稱, Value: 參數值)")
    private Map<String, String> annotations;

    @JsonPropertyDescription("【Root Only】此調用鏈涉及的所有 DTO 結構定義 (Key: ClassName, Value: Code/Structure)")
    private Map<String, String> relatedClasses;

    @JsonPropertyDescription("此方法呼叫了哪些其他方法")
    private List<CallGraph> calledMethods;

    // --- Static Factories ---

    /**
     * 終端節點 — 無子方法，不附帶原始碼
     * desc 為 null 時自動使用 CallType 預設描述
     */
    public static CallGraph leaf(String signature, String className, String methodName,
            CallType callType, String desc) {
        return CallGraph.builder()
                .signature(signature)
                .className(className)
                .methodName(methodName)
                .callType(callType)
                .desc(desc != null ? desc : callType.getDefaultDesc())
                .build();
    }

    /**
     * 終端節點 — 附帶原始碼或 SQL
     * 用於 DATA_ACCESS（SQL）、TRAVERSAL_CUTOFF（完整方法原始碼）
     */
    public static CallGraph leafWithCode(String signature, String className, String methodName,
            CallType callType, String desc, String code) {
        return CallGraph.builder()
                .signature(signature)
                .className(className)
                .methodName(methodName)
                .callType(callType)
                .desc(desc != null ? desc : callType.getDefaultDesc())
                .code(code)
                .build();
    }

    /** 分支節點 — 有子方法（如 INTERFACE），calledMethods 由呼叫端填充 */
    public static CallGraph branch(String signature, String className, String methodName,
            CallType callType) {
        return CallGraph.builder()
                .signature(signature)
                .className(className)
                .methodName(methodName)
                .callType(callType)
                .calledMethods(new ArrayList<>())
                .build();
    }
}
