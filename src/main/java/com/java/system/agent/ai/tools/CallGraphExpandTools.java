package com.java.system.agent.ai.tools;

import com.java.system.agent.analysis.AnalysisService;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisMetadata;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Slf4j
public class CallGraphExpandTools {

    private final AnalysisService analysisService;

    public CallGraphExpandTools(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Tool(name = ToolNames.FIND_CALL_GRAPH,
          description = "Expand a truncated or uncertain call graph node and return explainable nodes, edges, resolution strategy, confidence, and warnings.")
    public AnalysisResult<ExplainableCallGraph> findCallGraph(
            @ToolParam(description = "Repository id, e.g. 'bonus-service'") String repoId,
            @ToolParam(description = "Package name in dot notation, e.g. 'com.java.idcbonus.service'") String packageName,
            @ToolParam(description = "Class name, e.g. 'BonusService'") String className,
            @ToolParam(description = "Method name or full signature, e.g. 'calculate'") String methodSignature) {

        log.debug("CallGraphExpandTools expanding {}.{}", className, methodSignature);

        try {
            return analysisService.analyzeMethodExplainableStructured(repoId, packageName, className, methodSignature);
        } catch (Exception e) {
            log.error("CallGraphExpandTools failed for {}.{}: {}", className, methodSignature, e.getMessage());
            return AnalysisResult.failed(
                    AnalysisErrorCode.INTERNAL_ERROR,
                    "Call graph expansion failed",
                    e.getMessage(),
                    AnalysisMetadata.now(repoId, packageName, className, methodSignature));
        }
    }
}
