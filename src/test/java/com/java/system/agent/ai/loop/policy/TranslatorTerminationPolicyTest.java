package com.java.system.agent.ai.loop.policy;

import com.java.system.agent.ai.loop.LoopDecision;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.TerminationDecision;
import com.java.system.agent.ai.loop.TerminationReason;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranslatorTerminationPolicyTest {

    private final TranslatorTerminationPolicy policy = new TranslatorTerminationPolicy(2, 60_000);

    @Test
    void maxTurns_forceFinalizes() {
        LoopState state = new LoopState(new LoopRequest("t", "q"), 2, List.of(), List.of(
                new LoopStep(0, "查詢", List.of("find_call_graph"), null),
                new LoopStep(1, "回答", List.of(), Verdict.revise("再整理"))), System.currentTimeMillis());

        assertThat(policy.decide(state)).isEqualTo(TerminationDecision.terminate(
                LoopDecision.FORCE_FINALIZE, TerminationReason.MAX_TURNS));
    }

    @Test
    void wallClockExceeded_forceFinalizes() {
        long startedAtMillis = System.currentTimeMillis() - 61_000;
        LoopState state = new LoopState(new LoopRequest("t", "q"), 0, List.of(), List.of(), startedAtMillis);

        assertThat(policy.decide(state)).isEqualTo(TerminationDecision.terminate(
                LoopDecision.FORCE_FINALIZE, TerminationReason.TRANSLATOR_TIMEOUT));
    }

    @Test
    void withinBudgets_continues() {
        LoopState state = LoopState.init(new LoopRequest("t", "q"));

        assertThat(policy.decide(state)).isEqualTo(TerminationDecision.continueLoop());
    }
}
