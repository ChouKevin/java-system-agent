package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.port.RepoDocPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Stateless tool provider — LLM 用來讀取 service-map、business-map、skill docs。 */
@Slf4j
@Component
public class DocumentTools {

    private final RepoDocPort repoDocPort;

    public DocumentTools(RepoDocPort repoDocPort) {
        this.repoDocPort = repoDocPort;
    }

    @Tool(name = ToolNames.READ_SERVICE_MAP,
          description = "讀取 service-map.md，取得系統所有 repo 的業務概覽，用於初步判斷目標 repo")
    public String readServiceMap(ToolContext toolContext) {
        recordCall(toolContext, ToolNames.READ_SERVICE_MAP, "{}");
        return repoDocPort.readServiceMap();
    }

    @Tool(name = ToolNames.READ_BUSINESS_MAP,
          description = "讀取指定 repo 的 business-map.md，取得該 repo 的業務群組清單")
    public String readBusinessMap(
            @ToolParam(description = "目標 repo 的名稱，例如 bonus-service") String repoId,
            ToolContext toolContext) {
        recordCall(toolContext, ToolNames.READ_BUSINESS_MAP,
                String.format("{\"repoId\":\"%s\"}", repoId));
        String content = repoDocPort.readBusinessMap(repoId);
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return "========================================\n"
                + "## 專案: " + repoId + "\n"
                + content + "\n";
    }

    @Tool(name = ToolNames.READ_SKILL_DOC,
          description = "讀取指定業務群組的 skill 文件，取得詳細業務說明與進入點資訊")
    public String readSkillDoc(
            @ToolParam(description = "目標 repo 的名稱") String repoId,
            @ToolParam(description = "業務群組名稱，例如 event、rank") String groupName,
            ToolContext toolContext) {
        recordCall(toolContext, ToolNames.READ_SKILL_DOC,
                String.format("{\"repoId\":\"%s\",\"groupName\":\"%s\"}", repoId, groupName));
        String content = repoDocPort.readSkillDoc(repoId, groupName);
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return "========================================\n"
                + "## 業務群組: " + groupName + " (" + repoId + ")\n"
                + content + "\n";
    }

    private void recordCall(ToolContext toolContext, String toolName, String argsJson) {
        Object recorderObj = toolContext.getContext().get("recorder");
        if (recorderObj instanceof ToolCallRecorder recorder) {
            recorder.record(toolName, argsJson);
        }
    }
}
