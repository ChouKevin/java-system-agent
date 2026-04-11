package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.FlattenedCallGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/** 供 inner LLM 展開截斷的 call graph 節點。 */
@Slf4j
public class CallGraphExpandTools {

    private final AnalysisService analysisService;

    public CallGraphExpandTools(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Tool(name = ToolNames.FIND_CALL_GRAPH,
          description = "展開呼叫節點以取得更多業務細節。" +
                  "當節點 callType 為 TRAVERSAL_CUTOFF 且資訊不足時，從其 callees 的 signature 解析參數後呼叫；" +
                  "或遇到資料存取節點（DATA_ACCESS）時一律呼叫")
    public FlattenedCallGraph findCallGraph(
            @ToolParam(description = "目標 Repository 的名稱，例如: 'bonus-service'") String repoId,
            @ToolParam(description = "Package name in dot notation (e.g. 'com.java.idcbonus.service')") String packageName,
            @ToolParam(description = "Class name (e.g. 'BonusService')") String className,
            @ToolParam(description = "Method name or full signature (e.g. 'calculate')") String methodSignature) {

        log.debug("CallGraphExpandTools expanding {}.{}", className, methodSignature);

        try {
            FlattenedCallGraph result = analysisService.analyzeMethod(
                    repoId, packageName, className, methodSignature);
            if (result == null) {
                return FlattenedCallGraph.builder().methods(List.of()).build();
            }
            return result;
        } catch (Exception e) {
            log.error("CallGraphExpandTools failed for {}.{}", className, methodSignature, e);
            return FlattenedCallGraph.builder().methods(List.of()).build();
        }
    }
}
