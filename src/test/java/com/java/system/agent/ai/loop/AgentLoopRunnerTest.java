package com.java.system.agent.ai.loop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopRunnerTest {

    @Test
    void actThenFinal_actTurnSkipsVerifyThenAccepts() {
        AtomicInteger turn = new AtomicInteger();
        StepExecutor stepExecutor = state -> turn.getAndIncrement() == 0
                ? StepOutcome.acted("查詢", List.of(ToolCallRecord.of("read_service_map")))
                : StepOutcome.finalCandidate("完成回答", new Candidate("好答案"));
        AtomicInteger verifyCalls = new AtomicInteger();
        VerifyGate verifyGate = (candidate, state) -> {
            verifyCalls.incrementAndGet();
            return Verdict.accept();
        };
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> LoopDecision.CONTINUE, verifyGate, "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(verifyCalls).hasValue(1);
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().accepted()).isTrue();
        assertThat(done.result().finalAnswer()).isEqualTo("好答案");
        assertThat(done.result().steps()).hasSize(2);
        assertThat(done.result().toolCalls()).containsExactly(ToolCallRecord.of("read_service_map"));
    }

    @Test
    void rejectThenAccept_reflectsWithCritique() {
        AtomicInteger turn = new AtomicInteger();
        StepExecutor stepExecutor = state -> {
            assertThat(state.critiques()).hasSize(turn.get());
            return StepOutcome.finalCandidate("整理回覆", new Candidate("答案" + turn.getAndIncrement()));
        };
        VerifyGate verifyGate = (candidate, state) -> state.iteration() == 0
                ? Verdict.revise("證據不足")
                : Verdict.accept();
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> LoopDecision.CONTINUE, verifyGate, "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo("答案1");
        assertThat(done.result().steps()).extracting(LoopStep::verdict)
                .containsExactly(Verdict.revise("證據不足"), Verdict.accept());
    }

    @Test
    void acceptedCandidate_emitsTokenAndAcceptedTrace() {
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("可以申請")),
                state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(),
                "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).contains(new LoopEvent.Progress("完成回答"), new LoopEvent.Token("可以申請"));
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo("可以申請");
        assertThat(done.result().accepted()).isTrue();
    }

    @Test
    void forceFinalize_emitsPriorDraft() {
        AtomicInteger stepCalls = new AtomicInteger();
        StepExecutor stepExecutor = state -> {
            stepCalls.incrementAndGet();
            return StepOutcome.finalCandidate("產生草稿", new Candidate("先用這版回答"));
        };
        TerminationPolicy terminationPolicy = state -> state.iteration() == 0
                ? LoopDecision.CONTINUE
                : LoopDecision.FORCE_FINALIZE;
        AgentLoop loop = new AgentLoopRunner(stepExecutor, terminationPolicy,
                (candidate, state) -> Verdict.revise("再更精準"), "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(stepCalls).hasValue(1);
        assertThat(events).contains(new LoopEvent.Token("先用這版回答"));
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo("先用這版回答");
        assertThat(done.result().accepted()).isFalse();
    }

    @Test
    void stopBeforeAnyStep_emitsFallback() {
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("不應執行", new Candidate("x")),
                state -> LoopDecision.STOP,
                (candidate, state) -> Verdict.accept(),
                "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).contains(new LoopEvent.Token(AgentLoopRunner.FALLBACK));
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.FALLBACK);
        assertThat(done.result().accepted()).isFalse();
        assertThat(done.result().steps()).isEmpty();
    }

    @Test
    void forceFinalize_forcesAnswerWhenNoPriorDraft() {
        StepExecutor stepExecutor = new StepExecutor() {
            @Override
            public StepOutcome step(LoopState state) {
                return StepOutcome.acted("查詢", List.of(ToolCallRecord.of("find_call_graph")));
            }

            @Override
            public Candidate forceAnswer(LoopState state) {
                return new Candidate("直接回答");
            }
        };
        AgentLoop loop = new AgentLoopRunner(stepExecutor,
                state -> LoopDecision.FORCE_FINALIZE,
                (candidate, state) -> Verdict.accept(),
                "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).contains(new LoopEvent.Token("直接回答"));
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo("直接回答");
        assertThat(done.result().accepted()).isFalse();
    }

    @Test
    void run_stampsTraceIdAndRole() {
        List<LoopEvent> events = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("p", new Candidate("ans")),
                state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(),
                "analyst")
                .run(new LoopRequest("t", "q"))
                .collectList()
                .block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().role()).isEqualTo("analyst");
        assertThat(done.result().traceId()).isNotBlank();
    }

    @Test
    void actTurn_carriesChildTracesIntoLoopStep() {
        LoopTrace child = new LoopTrace("child-1", "translator", "子答案", true, List.of(), List.of());
        AtomicInteger turn = new AtomicInteger();
        StepExecutor stepExecutor = state -> turn.getAndIncrement() == 0
                ? StepOutcome.acted("查詢", List.of(ToolCallRecord.of("find_call_graph")), List.of(child))
                : StepOutcome.finalCandidate("p", new Candidate("ans"));

        List<LoopEvent> events = new AgentLoopRunner(
                stepExecutor,
                state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(),
                "analyst")
                .run(new LoopRequest("t", "q"))
                .collectList()
                .block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        LoopStep actStep = done.result().steps().getFirst();
        assertThat(actStep.childTraces()).containsExactly(child);
    }

    @Test
    void stepThrows_emitsBestEffortDoneWithFailedStepTrace() {
        StepExecutor stepExecutor = state -> {
            throw new IllegalStateException("model unavailable");
        };
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(), "test", saved::add);

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().accepted()).isFalse();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.FALLBACK);
        assertThat(done.result().steps()).hasSize(1);
        assertThat(done.result().steps().getFirst().summary()).contains("model unavailable");
        assertThat(saved).hasSize(1);
    }

    @Test
    void normalCompletion_notifiesTraceListener() {
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("答案")),
                state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(),
                "test", saved::add);

        loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().finalAnswer()).isEqualTo("答案");
    }

    @Test
    void traceListenerThrows_stillEmitsDone() {
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("答案")),
                state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(),
                "test",
                trace -> {
                    throw new IllegalStateException("store unavailable");
                });

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo("答案");
        assertThat(done.result().accepted()).isTrue();
    }

    @Test
    void cancelledRun_stillNotifiesTraceListener() {
        StepExecutor stepExecutor = state -> StepOutcome.acted(
                "查詢", List.of(ToolCallRecord.of("read_service_map")));
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> LoopDecision.CONTINUE,
                (candidate, state) -> Verdict.accept(), "test", saved::add);

        loop.run(new LoopRequest("t", "q")).take(1).blockLast();

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().accepted()).isFalse();
    }
}
