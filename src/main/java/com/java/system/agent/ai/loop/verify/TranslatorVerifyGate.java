package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 翻譯結果把關:未解析/低信心的邊必須展開或標示不確定性
 *
 * 直接檢查 typed 呼叫圖,不比對序列化後的 JSON 字串
 * 舊版比對 {@code "confidence":"LOW"},但 confidence 是 double,該分支永遠不成立
 */
public final class TranslatorVerifyGate implements VerifyGate {

    /**
     * 低於此信心值的呼叫邊視為不確定
     *
     * 分析器實際輸出的信心值落在兩群:0.70 以下為推測/未解析目標(部分帶有分析器警告,
     * 例如 HEURISTIC_NAME_MATCH 或缺乏 MyBatis 證據的資料存取),0.80 以上則為已解析目標
     * (0.80 為 Lombok 產生存取方法的信心值)
     * 兩群之間存在空隙,此門檻取在空隙中,避免卡在任何實際輸出值的邊界上
     */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.75;

    private final AnalysisResult<ExplainableCallGraph> analysisResult;

    public TranslatorVerifyGate(AnalysisResult<ExplainableCallGraph> analysisResult) {
        this.analysisResult = analysisResult;
    }

    @Override
    public Verdict verify(Candidate candidate, LoopState state) {
        if (hasUncertainEdge() && !acknowledgesUncertainty(candidate)) {
            return Verdict.revise("尚有未解析或低信心的呼叫，請展開或明確標示不確定性");
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
        return ResolutionStrategy.UNRESOLVED == edge.resolutionStrategy()
                || edge.confidence() < LOW_CONFIDENCE_THRESHOLD;
    }

    private boolean acknowledgesUncertainty(Candidate candidate) {
        String answer = Objects.toString(candidate.answer(), "");
        return StringUtils.hasText(answer)
                && (answer.contains("不確定")
                || answer.contains("無法確認")
                || answer.contains("可能"));
    }
}
