package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 翻譯結果把關:推測或無法驗證的邊必須展開或標示不確定性
 *
 * 直接檢查 typed 呼叫圖的解析策略,不比對序列化後的 JSON 字串
 */
public final class TranslatorVerifyGate implements VerifyGate {

    private final AnalysisResult<ExplainableCallGraph> analysisResult;

    public TranslatorVerifyGate(AnalysisResult<ExplainableCallGraph> analysisResult) {
        this.analysisResult = analysisResult;
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        if (hasUncertainEdge() && !acknowledgesUncertainty(candidate)) {
            return Verdict.revise("尚有推測或無法驗證的呼叫，請展開或明確標示不確定性");
        }
        return Verdict.accept();
    }

    private boolean hasUncertainEdge() {
        if (Objects.isNull(analysisResult) || Objects.isNull(analysisResult.data())) {
            return false;
        }
        List<CallEdge> edges = analysisResult.data().edges();
        if (CollectionUtils.isEmpty(edges)) {
            return false;
        }
        return edges.stream().anyMatch(TranslatorVerifyGate::isUncertain);
    }

    private static boolean isUncertain(CallEdge edge) {
        return edge.resolutionStrategy().isGuessed();
    }

    private boolean acknowledgesUncertainty(Candidate candidate) {
        String answer = Objects.toString(candidate.answer(), "");
        return StringUtils.hasText(answer)
                && (answer.contains("不確定")
                || answer.contains("無法確認")
                || answer.contains("可能"));
    }
}
