package com.java.system.agent.analysis.entrypoint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.java.system.agent.analysis.model.EntryPointType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryPointSelectionResult {
    @JsonPropertyDescription("選定的 Entry Point 列表，包含類別名稱、方法名稱、package 路徑和相關性原因")
    private List<SelectedEntryPoint> selectedEntryPoints;

    /**
     * AI 的推理過程描述
     */
    @JsonPropertyDescription("AI 的推理過程，解釋為什麼選擇這些 Entry Points")
    private String reasoning;

    /**
     * 是否找到相關的 Entry Points
     */
    public boolean hasResults() {
        return selectedEntryPoints != null && !selectedEntryPoints.isEmpty();
    }

    /**
     * 將結果格式化為易讀的訊息
     */
    public String toFormattedString() {
        if (!hasResults()) {
            return String.format("未找到相關的入口點。\n推理：%s", reasoning);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已識別相關入口點：\n");
        int index = 1;
        for (SelectedEntryPoint ep : selectedEntryPoints) {
            sb.append(String.format("%d. `%s.%s`\n", index++, ep.getClassName(), ep.getMethodName()));
            sb.append(String.format("   路徑: %s\n", ep.getPackagePath()));
            if (ep.getRelevanceReason() != null && !ep.getRelevanceReason().isEmpty()) {
                sb.append(String.format("   原因: %s\n", ep.getRelevanceReason()));
            }
        }
        sb.append(String.format("\n推理：%s", reasoning));
        return sb.toString();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelectedEntryPoint {

        @JsonPropertyDescription("專案名稱")
        private String repoId;

        @JsonPropertyDescription("類別名稱")
        private String className;

        @JsonPropertyDescription("方法名稱")
        private String methodName;

        @JsonPropertyDescription("package 路徑（例如：com/project/module/api/controller/ClassName.java）")
        private String packagePath;

        @JsonPropertyDescription("為什麼這個 Entry Point 與查詢相關")
        private String relevanceReason;

        @JsonPropertyDescription("AI 判斷此入口的類型 (API, JOB, MQ)")
        private EntryPointType type;
    }
}
