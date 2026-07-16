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
    void should_revise_when_edge_confidence_is_low_and_answer_is_certain() {
        // CallGraphBuilder emits 0.60 when an interface has multiple implementations,
        // i.e. the call target was guessed. This must force the translator to hedge.
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.INTERFACE_MULTI_IMPL, 0.60);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("未解析或低信心");
    }

    @Test
    void should_revise_when_edge_is_unresolved_and_answer_is_certain() {
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.UNRESOLVED, 0.90);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isFalse();
    }

    @Test
    void should_accept_when_edge_is_uncertain_and_answer_acknowledges_it() {
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.UNRESOLVED, 0.60);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(HEDGED_ANSWER), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_when_every_edge_is_resolved_and_confident() {
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.JAVA_SYMBOL_SOLVER, 0.90);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(CERTAIN_ANSWER), state());

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
    void should_revise_when_edge_confidence_is_heuristic_boundary_and_answer_is_certain() {
        // 0.70 is HEURISTIC_NAME_MATCH's emitted value, i.e. a guessed target;
        // the strict "<" comparison at the old 0.70 threshold let this slip through as confident.
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.HEURISTIC_NAME_MATCH, 0.70);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).contains("未解析或低信心");
    }

    @Test
    void should_revise_when_edge_confidence_is_below_heuristic_and_answer_is_certain() {
        // 0.65 is emitted for PARAMETER/LOCAL_VARIABLE receivers with a receiver-type
        // inference warning attached, i.e. also a guessed target.
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.HEURISTIC_NAME_MATCH, 0.65);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isFalse();
    }

    @Test
    void should_accept_when_edge_confidence_is_single_impl_and_answer_is_certain() {
        // 0.85 is INTERFACE_SINGLE_IMPL_CONFIDENCE, a resolved value.
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.INTERFACE_SINGLE_IMPL, 0.85);

        Verdict verdict = new TranslatorVerifyGate(result)
                .verify(new Candidate(CERTAIN_ANSWER), state());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void should_accept_when_edge_confidence_is_lombok_generated_and_answer_is_certain() {
        // 0.80 is emitted for LOMBOK_GENERATED_METHOD with ResolutionStrategy.UNKNOWN and no
        // warnings, i.e. the lowest resolved (non-guessed) value; it must clear the threshold.
        AnalysisResult<ExplainableCallGraph> result =
                graphWith(ResolutionStrategy.UNKNOWN, 0.80);

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
        return new MethodId("test-repo", "com.example.order", className, methodName, List.of());
    }

    private LoopState state() {
        return LoopState.init(new LoopRequest("t", "q"));
    }
}
