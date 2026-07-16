package com.java.system.agent.ai.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
                List.of(new LoopStep(0, "回答", List.of(), Verdict.accept())),
                List.of(ToolCallRecord.of("read_service_map")));

        assertThat(trace.toJson(new ObjectMapper().findAndRegisterModules()))
                .contains("\"finalAnswer\":\"答案\"")
                .contains("\"createdAt\"");
    }

    @Test
    void loopStep_exposesCandidateTimestamp() {
        assertThat(Arrays.stream(LoopStep.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("createdAt");
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

    @Test
    void plainFinalAnswer_stripsUnverifiedNote() {
        LoopTrace trace = new LoopTrace("trace-1", "translator",
                AgentLoopRunner.UNVERIFIED_NOTE + "原始答案", false, List.of(), List.of(),
                TerminationReason.TRANSLATOR_TIMEOUT);

        assertThat(trace.plainFinalAnswer()).isEqualTo("原始答案");
    }

    @Test
    void plainFinalAnswer_keepsAnswerWithoutNote() {
        LoopTrace trace = new LoopTrace("trace-1", "translator", "原始答案", true, List.of(), List.of());

        assertThat(trace.plainFinalAnswer()).isEqualTo("原始答案");
    }

    @Test
    void plainFinalAnswer_returnsEmpty_whenAnswerIsNull() {
        LoopTrace trace = new LoopTrace("trace-1", "translator", null, false, List.of(), List.of(),
                TerminationReason.STEP_ERROR);

        assertThat(trace.plainFinalAnswer()).isEmpty();
    }

    @Test
    void legacyConstructor_rejectsUnacceptedTraceWithoutExplicitTerminationReason() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LoopTrace(
                        "trace-1", "translator", "草稿", false, List.of(), List.of()))
                .withMessage("legacy LoopTrace constructor requires an accepted trace");
    }

    @Test
    void should_copy_trace_when_evidence_metadata_is_added() {
        LoopTrace original = new LoopTrace(
                "answer", true, List.of(), List.of());

        LoopTrace enriched = original.withMetadata(Map.of(
                "evidenceOutcome", "VERIFIED"));

        assertThat(original.metadata()).isEmpty();
        assertThat(enriched.metadata())
                .containsEntry("evidenceOutcome", "VERIFIED");
        assertThat(enriched.traceId()).isEqualTo(original.traceId());
        assertThat(enriched.role()).isEqualTo(original.role());
        assertThat(enriched.finalAnswer()).isEqualTo(original.finalAnswer());
        assertThat(enriched.accepted()).isEqualTo(original.accepted());
    }
}
