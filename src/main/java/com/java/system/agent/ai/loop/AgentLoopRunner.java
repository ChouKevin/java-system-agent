package com.java.system.agent.ai.loop;

import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AgentLoopRunner implements AgentLoop {

    public static final String FALLBACK = "目前資訊不足，無法完成分析";

    private final StepExecutor stepExecutor;
    private final TerminationPolicy terminationPolicy;
    private final VerifyGate verifyGate;
    private final String role;

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy, VerifyGate verifyGate) {
        this(stepExecutor, terminationPolicy, verifyGate, "loop");
    }

    public AgentLoopRunner(StepExecutor stepExecutor, TerminationPolicy terminationPolicy,
                           VerifyGate verifyGate, String role) {
        this.stepExecutor = stepExecutor;
        this.terminationPolicy = terminationPolicy;
        this.verifyGate = verifyGate;
        this.role = role;
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
                    String answer = safeAnswer(lastAnswer);
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }

                LoopDecision decision = terminationPolicy.decide(state);
                if (decision.isStop()) {
                    String answer = safeAnswer(lastAnswer);
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }
                if (decision.isForceFinalize()) {
                    Candidate candidate = StringUtils.hasText(lastAnswer)
                            ? new Candidate(lastAnswer)
                            : stepExecutor.forceAnswer(state);
                    String answer = safeAnswer(candidate);
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, false, state, allToolCalls));
                    sink.complete();
                    return;
                }

                StepOutcome outcome = stepExecutor.step(state);
                sink.next(new LoopEvent.Progress(outcome.progressLine()));
                allToolCalls.addAll(outcome.toolCalls());

                Verdict verdict = null;
                if (outcome.isFinalCandidate()) {
                    lastAnswer = outcome.candidate().answer();
                    verdict = verifyGate.verify(outcome.candidate(), state);
                }

                state = state.recordStep(new LoopStep(
                        state.iteration(), outcome.progressLine(), outcome.toolNames(),
                        verdict, outcome.childTraces()));

                if (outcome.isFinalCandidate() && verdict.accepted()) {
                    String answer = safeAnswer(lastAnswer);
                    sink.next(new LoopEvent.Token(answer));
                    sink.next(done(traceId, answer, true, state, allToolCalls));
                    sink.complete();
                    return;
                }
                if (outcome.isFinalCandidate()) {
                    state = state.injectCritique(verdict.critique());
                }
            }
        });
    }

    private LoopEvent.Done done(String traceId, String answer, boolean accepted, LoopState state,
                                List<ToolCallRecord> toolCalls) {
        return new LoopEvent.Done(new LoopTrace(traceId, role, answer, accepted,
                state.history(), List.copyOf(toolCalls)));
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
}
