package com.java.system.agent.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.tools.AgentAnalysisTools;
import com.java.system.agent.ai.tools.DocumentTools;
import com.java.system.agent.ai.tools.RequestScopedToolCallRecorder;
import com.java.system.agent.ai.tools.ToolNames;
import com.java.system.agent.analysis.AnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/**
 * Agent-style AI service. Runs a single outer LLM call with DocumentTools (on-demand
 * doc fetching) and AgentAnalysisTools (inner LLM for code-to-business translation).
 * The inner LLM result is returned directly to the outer LLM; only the outer stream
 * is sent to Slack, eliminating duplicate output.
 *
 * Replaces the legacy AiService three-step pipeline.
 */
@Service
@Slf4j
public class AgentAiService {

    private final ChatClient outerChatClient;
    private final ChatClient innerChatClient;
    private final DocumentTools documentTools;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;

    public AgentAiService(ChatClient.Builder chatClientBuilder,
                          ChatMemory chatMemory,
                          DocumentTools documentTools,
                          AnalysisService analysisService,
                          ObjectMapper objectMapper) {
        // Build inner client first (clean, no default advisors),
        // then add advisors to outer client only.
        this.innerChatClient = chatClientBuilder.build();

        PromptChatMemoryAdvisor memoryAdvisor = PromptChatMemoryAdvisor.builder(chatMemory).build();
        this.outerChatClient = chatClientBuilder
                .defaultAdvisors(new SimpleLoggerAdvisor(), memoryAdvisor)
                .build();

        this.documentTools = documentTools;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs the agent pipeline for a user query.
     * Returns a single Flux of outer LLM chunks with tool-call summaries appended at the end.
     */
    public Flux<String> analyzeWithTools(String conversationId, String userQuery) {
        RequestScopedToolCallRecorder recorder = new RequestScopedToolCallRecorder(objectMapper);
        AgentAnalysisTools agentAnalysisTools = new AgentAnalysisTools(
                analysisService, innerChatClient, objectMapper);

        return outerChatClient.prompt()
                .system(systemPrompt())
                .user(userQuery)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of("recorder", recorder, "userQuery", userQuery))
                .tools(documentTools, agentAnalysisTools)
                .stream()
                .content()
                .onErrorResume(e -> {
                    log.error("Outer LLM stream error for query: {}", userQuery, e);
                    return Flux.just("\n\n❌ 分析發生錯誤: " + e.getMessage());
                })
                .concatWith(Mono.fromCallable(() -> recorder.getSummaryForTools(
                                Set.of(
                                        ToolNames.READ_SERVICE_MAP,
                                        ToolNames.READ_BUSINESS_MAP,
                                        ToolNames.READ_BUSINESS_GROUP_DOC),
                                "📚 文件查閱紀錄"))
                        .filter(StringUtils::hasText))
                .concatWith(Mono.fromCallable(() -> recorder.getSummaryForTools(
                                Set.of(ToolNames.FIND_CALL_GRAPH),
                                "📋 程式碼查詢紀錄"))
                        .filter(StringUtils::hasText));
    }

    private String systemPrompt() {
        return """
                你是資深業務分析師，協助 PM、QA 與外部團隊了解系統業務流程。
                你的目標是完整回答使用者的問題。自主使用可用的 tool 取得所需資訊，不需等使用者確認即可連續呼叫多個 tool。
                每次取得 tool 結果後，評估是否足以回答；若不足，繼續呼叫其他 tool，直到有足夠依據為止。

                可用 tool 與使用指引：
                - read_service_map：取得系統所有 repo 的業務概覽。用於判斷目標 repo，若使用者已明確指定 repo 則跳過
                - read_business_map(repoId)：取得指定 repo 的業務群組清單。用於定位相關業務群組
                - read_business_group_doc(repoId, groupName)：取得業務群組的詳細進入點說明。用於找出具體的進入點方法
                - find_call_graph(repoId, packageName, className, methodSignature)：分析指定方法的業務流程與判斷條件
                  - 方法名稱必須來自 read_business_group_doc 文件中明確列出的進入點，禁止傳入編造的名稱（如 "all methods"、"*"）
                  - 查詢整個 Controller 或類別時，先用 read_business_group_doc 找出所有進入點，再逐一呼叫
                  - 當使用者詢問業務邏輯、流程、規則、判斷、計算、條件時，必須呼叫此 tool 取得依據

                自主決策原則：
                - 對話歷史中已取得的資訊直接引用，不必重複呼叫相同 tool
                - 問題模糊或跨 repo 時，從 read_service_map 開始逐步縮小範圍
                - 若無法判斷目標 repo，列出候選項請使用者確認
                - 若涉及多個 repo，分別查詢後整合回答
                - read_business_group_doc 不足以回答時，主動對相關進入點呼叫 find_call_graph
                - tool 回傳空結果時，告知使用者該文件尚未建立
                - 禁止在資訊不足時臆測或編造業務邏輯；誠實告知不足之處

                嚴格輸出規則：
                - 禁止提及程式碼、類別名稱、方法名稱、套件路徑、技術實作細節
                - 禁止提及資料表名稱、欄位名稱、SQL/Schema/Stored Procedure 名稱
                - 以業務流程、資料流、系統行為、觸發條件說明
                - 使用繁體中文，Markdown 格式
                - 對象為 PM、QA、外部團隊，使用業務語言
                - 回答時可以說明「以下為系統內部邏輯推導」，但不可直接提及程式碼細節
                """;
    }
}
