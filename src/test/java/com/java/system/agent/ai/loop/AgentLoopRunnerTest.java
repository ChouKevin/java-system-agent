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
        AgentLoop loop = new AgentLoopRunner(
                stepExecutor, state -> TerminationDecision.continueLoop(), verifyGate, "test");

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
        AgentLoop loop = new AgentLoopRunner(
                stepExecutor, state -> TerminationDecision.continueLoop(), verifyGate, "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo("答案1");
        assertThat(done.result().steps()).extracting(LoopStep::verdict)
                .containsExactly(Verdict.revise("證據不足"), Verdict.accept());
    }

    @Test
    void rejectedCandidate_emitsRevisionProgress() {
        AtomicInteger turn = new AtomicInteger();
        StepExecutor stepExecutor = state -> StepOutcome.finalCandidate(
                "整理回覆", new Candidate("答案" + turn.getAndIncrement()));
        VerifyGate verifyGate = (candidate, state) -> state.iteration() == 0
                ? Verdict.revise("證據不足")
                : Verdict.accept();
        AgentLoop loop = new AgentLoopRunner(
                stepExecutor, state -> TerminationDecision.continueLoop(), verifyGate, "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).containsSequence(
                new LoopEvent.Progress("整理回覆"),
                new LoopEvent.Progress(AgentLoopRunner.REVISION_NOTICE),
                new LoopEvent.Progress("整理回覆"),
                new LoopEvent.Token("答案1"));
    }

    @Test
    void acceptedCandidate_emitsTokenAndAcceptedTrace() {
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("可以申請")),
                state -> TerminationDecision.continueLoop(),
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
                ? TerminationDecision.continueLoop()
                : TerminationDecision.terminate(LoopDecision.FORCE_FINALIZE, TerminationReason.MAX_TURNS);
        AgentLoop loop = new AgentLoopRunner(stepExecutor, terminationPolicy,
                (candidate, state) -> Verdict.revise("再更精準"), "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(stepCalls).hasValue(1);
        assertThat(events).noneMatch(LoopEvent.Token.class::isInstance);
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.UNVERIFIED_NOTE + "先用這版回答");
        assertThat(done.result().accepted()).isFalse();
        assertThat(done.result().terminationReason()).isEqualTo(TerminationReason.MAX_TURNS);
    }

    @Test
    void should_apply_terminal_policy_when_force_finalize_uses_rejected_draft() {
        StepExecutor stepExecutor = state -> StepOutcome.finalCandidate(
                "產生草稿", new Candidate("未驗證業務結論"));
        TerminationPolicy terminationPolicy = state -> state.iteration() == 0
                ? TerminationDecision.continueLoop()
                : TerminationDecision.terminate(LoopDecision.FORCE_FINALIZE, TerminationReason.MAX_TURNS);
        AgentLoop loop = new AgentLoopRunner(
                stepExecutor,
                terminationPolicy,
                (candidate, state) -> Verdict.revise("缺少證據"),
                "test",
                trace -> {
                },
                candidate -> new Candidate("safe"));

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q"))
                .collectList()
                .block();

        assertThat(events).noneMatch(LoopEvent.Token.class::isInstance);
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.UNVERIFIED_NOTE + "safe");
    }

    @Test
    void should_not_apply_terminal_policy_when_candidate_is_normally_accepted() {
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("已接受結論")),
                state -> TerminationDecision.continueLoop(),
                (candidate, state) -> Verdict.accept(),
                "test",
                trace -> {
                },
                candidate -> new Candidate("safe"));

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q"))
                .collectList()
                .block();

        assertThat(events).contains(new LoopEvent.Token("已接受結論"));
        assertThat(events).doesNotContain(new LoopEvent.Token("safe"));
    }

    @Test
    void should_apply_terminal_policy_when_stop_uses_rejected_draft() {
        StepExecutor stepExecutor = state -> StepOutcome.finalCandidate(
                "產生草稿", new Candidate("未驗證業務結論"));
        TerminationPolicy terminationPolicy = state -> state.iteration() == 0
                ? TerminationDecision.continueLoop()
                : TerminationDecision.terminate(LoopDecision.STOP, TerminationReason.NO_PROGRESS);
        AgentLoop loop = new AgentLoopRunner(
                stepExecutor,
                terminationPolicy,
                (candidate, state) -> Verdict.revise("缺少證據"),
                "test",
                trace -> {
                },
                candidate -> new Candidate("safe"));

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q"))
                .collectList()
                .block();

        assertThat(events).noneMatch(LoopEvent.Token.class::isInstance);
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.UNVERIFIED_NOTE + "safe");
    }

    @Test
    void should_apply_terminal_policy_when_step_throws() {
        StepExecutor stepExecutor = state -> {
            throw new IllegalStateException("model unavailable");
        };
        AgentLoop loop = new AgentLoopRunner(
                stepExecutor,
                state -> TerminationDecision.continueLoop(),
                (candidate, state) -> Verdict.accept(),
                "test",
                trace -> {
                },
                candidate -> new Candidate("safe"));

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q"))
                .collectList()
                .block();

        assertThat(events).noneMatch(LoopEvent.Token.class::isInstance);
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.UNVERIFIED_NOTE + "safe");
        assertThat(done.result().terminationReason()).isEqualTo(TerminationReason.STEP_ERROR);
    }

    @Test
    void should_apply_terminal_policy_when_cancelled() {
        StepExecutor stepExecutor = state -> StepOutcome.acted(
                "查詢", List.of(ToolCallRecord.of("read_service_map")));
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(
                stepExecutor,
                state -> TerminationDecision.continueLoop(),
                (candidate, state) -> Verdict.accept(),
                "test",
                saved::add,
                candidate -> new Candidate("safe"));

        loop.run(new LoopRequest("t", "q")).take(1).blockLast();

        assertThat(saved).singleElement()
                .extracting(LoopTrace::finalAnswer)
                .isEqualTo(AgentLoopRunner.UNVERIFIED_NOTE + "safe");
        assertThat(saved.getFirst().terminationReason()).isEqualTo(TerminationReason.CANCELLED);
    }

    @Test
    void finalCandidateCancellation_recordsCancelledInsteadOfAcceptedTrace() {
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("不應送出的答案")),
                state -> TerminationDecision.continueLoop(),
                (candidate, state) -> Verdict.accept(),
                "test",
                saved::add);

        loop.run(new LoopRequest("t", "q")).take(1).blockLast();

        assertThat(saved).singleElement()
                .extracting(LoopTrace::terminationReason)
                .isEqualTo(TerminationReason.CANCELLED);
        assertThat(saved.getFirst().steps()).singleElement()
                .extracting(LoopStep::candidateAnswer)
                .isEqualTo("不應送出的答案");
    }

    @Test
    void stopBeforeAnyStep_emitsFallback() {
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("不應執行", new Candidate("x")),
                state -> TerminationDecision.terminate(LoopDecision.STOP, TerminationReason.NO_PROGRESS),
                (candidate, state) -> Verdict.accept(),
                "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).noneMatch(LoopEvent.Token.class::isInstance);
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
                state -> TerminationDecision.terminate(
                        LoopDecision.FORCE_FINALIZE, TerminationReason.MAX_TURNS),
                (candidate, state) -> Verdict.accept(),
                "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).noneMatch(LoopEvent.Token.class::isInstance);
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.UNVERIFIED_NOTE + "直接回答");
        assertThat(done.result().accepted()).isFalse();
        assertThat(done.result().steps()).singleElement()
                .extracting(LoopStep::candidateAnswer)
                .isEqualTo("直接回答");
    }

    @Test
    void forceAnswerThrows_emitsFallbackInsteadOfError() {
        StepExecutor stepExecutor = new StepExecutor() {
            @Override
            public StepOutcome step(LoopState state) {
                return StepOutcome.acted("查詢", List.of(ToolCallRecord.of("find_call_graph")));
            }

            @Override
            public Candidate forceAnswer(LoopState state) {
                throw new IllegalStateException(
                        "Estimated LLM request tokens exceed configured tokens-per-minute limit");
            }
        };
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(stepExecutor,
                state -> TerminationDecision.terminate(
                        LoopDecision.FORCE_FINALIZE, TerminationReason.MAX_TURNS),
                (candidate, state) -> Verdict.accept(), "test", saved::add);

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.FALLBACK);
        assertThat(done.result().accepted()).isFalse();
        assertThat(saved).hasSize(1);
    }

    @Test
    void stopWithRejectedDraft_recordsCandidateAndReasonWithoutEmittingToken() {
        StepExecutor stepExecutor = state -> StepOutcome.finalCandidate(
                "整理回覆", new Candidate("被拒的草稿"));
        TerminationPolicy terminationPolicy = state -> state.iteration() < 1
                ? TerminationDecision.continueLoop()
                : TerminationDecision.terminate(LoopDecision.STOP, TerminationReason.NO_PROGRESS);
        AgentLoop loop = new AgentLoopRunner(stepExecutor, terminationPolicy,
                (candidate, state) -> Verdict.revise("臆測"), "test");

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        assertThat(events).noneMatch(LoopEvent.Token.class::isInstance);
        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().terminationReason()).isEqualTo(TerminationReason.NO_PROGRESS);
        assertThat(done.result().steps().getLast().candidateAnswer()).isEqualTo("被拒的草稿");
        assertThat(done.result().accepted()).isFalse();
    }

    @Test
    void run_stampsTraceIdAndRole() {
        LoopRequest request = new LoopRequest("trace-1", "t", "q");
        List<LoopEvent> events = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("p", new Candidate("ans")),
                state -> TerminationDecision.continueLoop(),
                (candidate, state) -> Verdict.accept(),
                "analyst")
                .run(request)
                .collectList()
                .block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().role()).isEqualTo("analyst");
        assertThat(done.result().traceId()).isEqualTo(request.traceId());
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
                state -> TerminationDecision.continueLoop(),
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
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> TerminationDecision.continueLoop(),
                (candidate, state) -> Verdict.accept(), "test", saved::add);

        List<LoopEvent> events = loop.run(new LoopRequest("t", "q")).collectList().block();

        LoopEvent.Done done = (LoopEvent.Done) events.getLast();
        assertThat(done.result().accepted()).isFalse();
        assertThat(done.result().finalAnswer()).isEqualTo(AgentLoopRunner.FALLBACK);
        assertThat(done.result().steps()).hasSize(1);
        assertThat(done.result().steps().getFirst().summary()).contains("model unavailable");
        assertThat(done.result().terminationReason()).isEqualTo(TerminationReason.STEP_ERROR);
        assertThat(saved).hasSize(1);
    }

    @Test
    void normalCompletion_notifiesTraceListener() {
        List<LoopTrace> saved = new ArrayList<>();
        AgentLoop loop = new AgentLoopRunner(
                state -> StepOutcome.finalCandidate("完成回答", new Candidate("答案")),
                state -> TerminationDecision.continueLoop(),
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
                state -> TerminationDecision.continueLoop(),
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
        AgentLoop loop = new AgentLoopRunner(stepExecutor, state -> TerminationDecision.continueLoop(),
                (candidate, state) -> Verdict.accept(), "test", saved::add);

        loop.run(new LoopRequest("t", "q")).take(1).blockLast();

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().accepted()).isFalse();
        assertThat(saved.getFirst().terminationReason()).isEqualTo(TerminationReason.CANCELLED);
    }
}
