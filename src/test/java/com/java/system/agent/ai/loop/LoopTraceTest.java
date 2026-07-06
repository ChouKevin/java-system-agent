package com.java.system.agent.ai.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoopTraceTest {

    @Test
    void summarisesIterationsAndRejections() {
        LoopTrace trace = new LoopTrace("trace-1", "analyst", "答案", true, List.of(
                new LoopStep(0, "查詢", List.of("read_service_map"), Verdict.revise("再查")),
                new LoopStep(1, "回答", List.of("find_call_graph"), Verdict.accept())), List.of());

        assertThat(trace.iterationCount()).isEqualTo(2);
        assertThat(trace.rejectionCount()).isEqualTo(1);
    }

    @Test
    void toJson_serializesTrace() {
        LoopTrace trace = new LoopTrace("trace-1", "analyst", "答案", true,
                List.of(), List.of(ToolCallRecord.of("read_service_map")));

        assertThat(trace.toJson(new ObjectMapper())).contains("\"finalAnswer\":\"答案\"");
    }

    @Test
    void totalTurns_countsNestedChildTraces() {
        LoopTrace child = new LoopTrace("child-1", "translator", "子答案", true,
                List.of(new LoopStep(0, "子查詢", List.of("find_call_graph"), null)), List.of());
        LoopTrace trace = new LoopTrace("trace-1", "analyst", "答案", true, List.of(
                new LoopStep(0, "查詢", List.of("find_call_graph"), null, List.of(child)),
                new LoopStep(1, "回答", List.of(), Verdict.accept())), List.of());

        assertThat(trace.totalTurns()).isEqualTo(3);
    }

    @Test
    void totalTokens_sumsStepsAndChildTraces() {
        LoopTrace child = new LoopTrace("child-1", "translator", "子答案", true,
                List.of(new LoopStep(0, "子查詢", List.of(), null,
                        List.of(), new StepMetrics(5, 10, 2))), List.of());
        LoopStep parentStep = new LoopStep(0, "查詢", List.of(), null,
                List.of(child), new StepMetrics(7, 100, 30));
        LoopTrace trace = new LoopTrace("trace-1", "analyst", "答案", true,
                List.of(parentStep), List.of());

        assertThat(trace.totalTokens()).isEqualTo(142L);
    }

    @Test
    void stepOutcome_legacyConstructorDefaultsMetrics() {
        StepOutcome outcome = new StepOutcome("查詢", null, List.of(), List.of());

        assertThat(outcome.metrics()).isEqualTo(StepMetrics.none());
    }
}
