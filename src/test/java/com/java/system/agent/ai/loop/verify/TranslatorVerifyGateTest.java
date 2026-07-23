package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.analysis.model.AnalysisErrorCode;
import com.java.system.agent.analysis.model.AnalysisResult;
import com.java.system.agent.analysis.model.CallEdge;
import com.java.system.agent.analysis.model.ExplainableCallGraph;
import com.java.system.agent.analysis.model.MethodId;
import com.java.system.agent.analysis.model.ResolutionStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TranslatorVerifyGateTest {

    private static final String CERTAIN_ANSWER = "系統會完成所有檢核";
    private static final String HEDGED_ANSWER = "系統可能還需要人工確認部分分支";

    @Test
    void should_revise_when_strategy_is_guessed_and_answer_is_certain() {
        Verdict verdict = verify(ResolutionStrategy.SPRING_MULTIPLE_CANDIDATES, 0.99, CERTAIN_ANSWER);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("推測或無法驗證");
    }

    @Test
    void should_accept_when_strategy_is_guessed_and_answer_is_hedged() {
        Verdict verdict = verify(ResolutionStrategy.UNRESOLVED_TARGET, 0.99, HEDGED_ANSWER);

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_when_strategy_is_resolved_despite_arbitrary_low_confidence() {
        Verdict verdict = verify(ResolutionStrategy.JDT_CALL_HIERARCHY, 0.01, CERTAIN_ANSWER);

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_revise_when_strategy_is_opaque_and_answer_is_certain() {
        Verdict verdict = verify(ResolutionStrategy.EXTERNAL_LIBRARY, 0.99, CERTAIN_ANSWER);

        assertThat(verdict.accepted()).isFalse();
    }

    @Test
    void should_accept_when_strategy_is_lombok_generated_and_answer_is_certain() {
        Verdict verdict = verify(ResolutionStrategy.LOMBOK_GENERATED, 0.01, CERTAIN_ANSWER);

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_when_analysis_result_is_null() {
        Verdict verdict = new TranslatorVerifyGate(null)
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_when_analysis_result_data_is_null() {
        AnalysisResult<ExplainableCallGraph> result = AnalysisResult.failed(
                AnalysisErrorCode.PARSE_FAILED, "parse failed", "detail", null);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_when_graph_has_no_edges() {
        ExplainableCallGraph graph =
                new ExplainableCallGraph(methodId("OrderService", "createOrder"), List.of(), List.of(), Map.of(), null);

        Verdict verdict = new TranslatorVerifyGate(AnalysisResult.success(graph, null))
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isTrue();
    }

    private static Verdict verify(ResolutionStrategy strategy, double confidence, String answer) {
        return new TranslatorVerifyGate(graphWith(strategy, confidence))
                .verify(new Candidate(answer), state());
    }

    private static AnalysisResult<ExplainableCallGraph> graphWith(
            ResolutionStrategy strategy, double confidence) {
        MethodId caller = methodId("OrderService", "createOrder");
        MethodId callee = methodId("OrderRepository", "save");
        CallEdge edge = new CallEdge(
                caller,
                callee,
                "orderRepository.save(order)",
                42,
                strategy,
                confidence,
                List.of("field type OrderRepository"),
                List.of());
        ExplainableCallGraph graph =
                new ExplainableCallGraph(caller, List.of(), List.of(edge), Map.of(), null);
        return AnalysisResult.success(graph, null);
    }

    private static MethodId methodId(String className, String methodName) {
        return new MethodId("demo-repo", "com.example.order", className, methodName, List.of());
    }

    private static LoopState state() {
        return LoopState.init(new LoopRequest("t", "q"));
    }
}
