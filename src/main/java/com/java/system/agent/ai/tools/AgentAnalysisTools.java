package com.java.system.agent.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.agent.ai.loop.AgentLoop;
import com.java.system.agent.ai.loop.AgentLoopRunner;
import com.java.system.agent.ai.loop.ChatModelStep;
import com.java.system.agent.ai.loop.LoopEvent;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopTrace;
import com.java.system.agent.ai.loop.LoopTraceCollector;
import com.java.system.agent.ai.loop.policy.TranslatorTerminationPolicy;
import com.java.system.agent.ai.loop.verify.TranslatorVerifyGate;
import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.AnalysisStatus;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Objects;

/**
 * Per-request tool — NOT a @Component.
 * Runs call graph analysis; translator loop is wired in a later task.
 */
@Slf4j
public class AgentAnalysisTools {

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final AnalysisService analysisService;
    private final ObjectMapper objectMapper;
    private final int translatorMaxTurns;
    private final long translatorMaxWallMillis;

    public AgentAnalysisTools(ChatModel chatModel,
                              ToolCallingManager toolCallingManager,
                              AnalysisService analysisService,
                              ObjectMapper objectMapper,
                              int translatorMaxTurns,
                              long translatorMaxWallMillis) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
        this.translatorMaxTurns = translatorMaxTurns;
        this.translatorMaxWallMillis = translatorMaxWallMillis;
    }

    @Tool(name = ToolNames.FIND_CALL_GRAPH,
          description = "分析指定方法的程式碼呼叫鏈，以業務語言說明其流程（不含任何程式碼細節）")
    public String findCallGraph(
            @ToolParam(description = "目標 Repository 的名稱，例如: 'bonus-service'") String repoId,
            @ToolParam(description = "Package name in dot notation (e.g. 'com.java.idcbonus.service')") String packageName,
            @ToolParam(description = "Class name (e.g. 'BonusService')") String className,
            @ToolParam(description = "Method name or full signature (e.g. 'calculate')") String methodSignature,
            ToolContext toolContext) {

        log.info("AgentAnalysisTools.findCallGraph: {}.{}.{}", repoId, className, methodSignature);

        AnalysisResult<ExplainableCallGraph> analysisResult = analysisService.analyzeMethodExplainableStructured(
                repoId, packageName, className, methodSignature);
        try {
            String callGraphJson = objectMapper.writeValueAsString(analysisResult);
            if (analysisResult.status() == AnalysisStatus.FAILED) {
                return callGraphJson;
            }
            return translateCallGraph(callGraphJson, toolContext);
        } catch (Exception e) {
            log.error("Call graph analysis serialization failed for {}.{}", className, methodSignature, e);
            return "（程式碼業務分析暫時無法取得）";
        }
    }

    private String translateCallGraph(String callGraphJson, ToolContext toolContext) {
        String userQuery = Objects.toString(toolContext.getContext().get("userQuery"), "");
        CallGraphExpandTools expandTools = new CallGraphExpandTools(analysisService);
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(expandTools))
                .internalToolExecutionEnabled(false)
                .build();
        List<Message> seed = List.of(
                new SystemMessage(innerSystemPrompt(userQuery)),
                new UserMessage(innerUserPrompt(callGraphJson)));

        ChatModelStep step = new ChatModelStep(chatModel, toolCallingManager, options, seed);
        AgentLoop loop = new AgentLoopRunner(
                step,
                new TranslatorTerminationPolicy(translatorMaxTurns, translatorMaxWallMillis),
                new TranslatorVerifyGate(callGraphJson),
                "translator");

        LoopTrace result = loop.run(new LoopRequest("translator", userQuery))
                .ofType(LoopEvent.Done.class)
                .map(LoopEvent.Done::result)
                .blockLast();
        if (Objects.isNull(result)) {
            return "（程式碼業務分析暫時無法取得）";
        }
        publishTrace(toolContext, result);
        return result.finalAnswer();
    }

    private void publishTrace(ToolContext toolContext, LoopTrace trace) {
        Object collector = toolContext.getContext().get("traceCollector");
        if (collector instanceof LoopTraceCollector traceCollector) {
            traceCollector.add(trace);
        }
    }

    private String innerSystemPrompt(String userQuery) {
        return """
                你是程式碼業務翻譯員。
                你的任務是將程式碼呼叫鏈（JSON 格式）翻譯為業務流程說明。

                對象：PM、QA、外部團隊（無程式碼背景）。

                使用者問題：%s

                展開策略（重要）：
                你有 find_call_graph 工具，可以展開呼叫節點以取得更多業務細節。

                使用規則：
                - 按需展開：callType 為 TRAVERSAL_CUTOFF 的節點代表呼叫鏈被截斷，其 callees 列出了被截斷的下層方法 signature。
                  當資訊不足以說明業務行為時，從 signature 解析出 packageName、className、methodName，呼叫 find_call_graph 展開。
                - SQL 強制展開：遇到資料存取節點（DATA_ACCESS 類型），無論深度是否截斷，一律呼叫 find_call_graph 展開，
                  因為業務判斷邏輯可能寫在 SQL 條件中（WHERE 條件、CASE WHEN、Stored Procedure 等）
                - 整合所有查詢結果後，輸出一份完整業務說明

                嚴格規則：
                - 禁止輸出任何類別名稱、方法名稱、套件路徑、程式碼片段
                - 禁止使用程式碼術語（如 Service、Controller、Repository、method）
                - 禁止提及資料表名稱、欄位名稱、SQL/Schema/Stored Procedure 名稱
                - 以業務動作、資料流、系統行為、觸發條件描述
                - 使用繁體中文，Markdown 條列格式
                """.formatted(userQuery);
    }

    private String innerUserPrompt(String callGraphJson) {
        return """
                請將以下程式碼呼叫鏈翻譯為業務流程說明：
                Explainable graph JSON guidance:
                - Read data.nodes for methods and data.edges for caller-to-callee relationships.
                - Read edge.evidence together with edge.resolutionStrategy when judging reliability.
                - Read node.sourceFile, node.startLine, node.endLine, edge.sourceFile, and edge.lineNumber as source coordinates.
                - Treat source coordinates as snapshot evidence, not stable identifiers after codebase updates.
                - Revalidate important coordinates with semantic keys such as repoId, packageName, className, methodName, signature, and callExpression before citing exact code locations.
                - Treat evidence as analyzer facts and warnings as uncertainty.
                - When evidence contains MULTIPLE_INTERFACE_IMPLEMENTATIONS, explain candidate ambiguity instead of naming one implementation as certain.
                - Use edge.resolutionStrategy, edge.confidence, edge.evidence, and edge.warnings when judging reliability.
                - Treat LOW confidence or UNRESOLVED edges as uncertainty, and call find_call_graph again when expansion is needed.

                %s
                """.formatted(callGraphJson);
    }
}
