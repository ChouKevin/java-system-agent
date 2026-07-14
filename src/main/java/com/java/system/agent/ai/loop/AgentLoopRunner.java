package com.java.system.agent.ai.loop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
public class AgentLoopRunner implements AgentLoop {

    public static final String FALLBACK = "目前資訊不足，無法完成分析";
    public static final String REVISION_NOTICE = "↻ 自我審查未過，修正中";
    public static final String UNVERIFIED_NOTE = "⚠️ 以下回答未通過完整自我審查，僅供參考\n\n";

    private final StepExecutor stepExecutor;
    private final TerminationPolicy terminationPolicy;
    private final VerifyGate verifyGate;
    private final String role;
    private final Consumer<LoopTrace> traceListener;
    private final TerminalAnswerPolicy terminalAnswerPolicy;

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy, VerifyGate verifyGate) {
        this(stepExecutor, terminationPolicy, verifyGate, "loop");
    }

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy,
                           VerifyGate verifyGate, String role) {
        this(stepExecutor, terminationPolicy, verifyGate, role, trace -> {
        });
    }

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy,
                           VerifyGate verifyGate, String role, Consumer<LoopTrace> traceListener) {
        this(stepExecutor, terminationPolicy, verifyGate, role, traceListener,
                TerminalAnswerPolicy.identity());
    }

    public AgentLoopRunner(
            StepExecutor stepExecutor,
            TerminationPolicy terminationPolicy,
            VerifyGate verifyGate,
            String role,
            Consumer<LoopTrace> traceListener,
            TerminalAnswerPolicy terminalAnswerPolicy) {
        this.stepExecutor = stepExecutor;
        this.terminationPolicy = terminationPolicy;
        this.verifyGate = verifyGate;
        this.role = role;
        this.traceListener = traceListener;
        this.terminalAnswerPolicy = terminalAnswerPolicy;
    }

    @Override
    public Flux<LoopEvent> run(LoopRequest request) {
        return Flux.create(sink -> {
            String traceId = UUID.randomUUID().toString();
            LoopState state = LoopState.init(request);
            List<ToolCallRecord> allToolCalls = new ArrayList<>();
            String lastAnswer = "";

            while (true) {
                if (sink.isCancelled()) {
                    String answer = governedTerminalAnswer(lastAnswer);
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }

                LoopDecision decision = terminationPolicy.decide(state);
                if (decision.isStop()) {
                    String answer = governedTerminalAnswer(lastAnswer);
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }
                if (decision.isForceFinalize()) {
                    Candidate candidate;
                    try {
                        candidate = StringUtils.hasText(lastAnswer)
                                ? new Candidate(lastAnswer)
                                : stepExecutor.forceAnswer(state);
                    } catch (RuntimeException exception) {
                        log.warn("[{}] forceAnswer failed, falling back to last known answer", role, exception);
                        candidate = new Candidate(lastAnswer);
                    }
                    String answer = governedTerminalAnswer(candidate.answer());
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }

                StepOutcome outcome;
                try {
                    outcome = stepExecutor.step(state);
                } catch (RuntimeException e) {
                    log.warn("[{}] step failed, finalizing with best-effort answer", role, e);
                    state = state.recordStep(new LoopStep(state.iteration(),
                            "模型呼叫失敗: " + e.getMessage(), List.of(), null));
                    String answer = governedTerminalAnswer(lastAnswer);
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }
                sink.next(new LoopEvent.Progress(outcome.progressLine()));
                allToolCalls.addAll(outcome.toolCalls());

                Verdict verdict = null;
                if (outcome.isFinalCandidate()) {
                    lastAnswer = outcome.candidate().answer();
                    verdict = verifyGate.verify(outcome.candidate(), state);
                }

                state = state.recordStep(new LoopStep(
                        state.iteration(), outcome.progressLine(), outcome.toolNames(),
                        verdict, outcome.childTraces(), outcome.metrics()));

                if (outcome.isFinalCandidate() && verdict.accepted()) {
                    String answer = safeAnswer(lastAnswer);
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, true, state, allToolCalls));
                    sink.complete();
                    return;
                }
                if (outcome.isFinalCandidate()) {
                    state = state.injectCritique(verdict.critique());
                    sink.next(new LoopEvent.Progress(REVISION_NOTICE));
                }
            }
        });
    }

    private LoopEvent.Done done(String traceId, String answer, boolean accepted, LoopState state,
                                List<ToolCallRecord> toolCalls) {
        LoopTrace trace = new LoopTrace(traceId, role, answer, accepted,
                state.history(), List.copyOf(toolCalls));
        try {
            traceListener.accept(trace);
        } catch (RuntimeException e) {
            log.warn("[{}] trace listener failed", role, e);
        }
        return new LoopEvent.Done(trace);
    }

    private String safeAnswer(Candidate candidate) {
        return safeAnswer(candidate.answer());
    }

    private String safeAnswer(String answer) {
        if (StringUtils.hasText(answer)) {
            return answer;
        }
        return FALLBACK;
    }

    private String governedTerminalAnswer(String answer) {
        Candidate governed = terminalAnswerPolicy.apply(new Candidate(safeAnswer(answer)));
        return markUnverified(safeAnswer(governed));
    }

    private String markUnverified(String answer) {
        if (FALLBACK.equals(answer)) {
            return answer;
        }
        return UNVERIFIED_NOTE + answer;
    }
}
