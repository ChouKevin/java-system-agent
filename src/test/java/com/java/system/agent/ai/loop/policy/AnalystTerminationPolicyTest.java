package com.java.system.agent.ai.loop.policy;

import com.java.system.agent.ai.loop.LoopDecision;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.LoopStep;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystTerminationPolicyTest {

    private final AnalystTerminationPolicy policy = new AnalystTerminationPolicy(3, 120_000, 2);

    @Test
    void maxTurns_forceFinalizes() {
        LoopState state = stateWithHistory(List.of(
                rejectedNoTool(0),
                rejectedNoTool(1),
                rejectedNoTool(2)));

        assertThat(policy.decide(state)).isEqualTo(LoopDecision.FORCE_FINALIZE);
    }

    @Test
    void wallClockExceeded_forceFinalizes() {
        long startedAtMillis = System.currentTimeMillis() - 121_000;
        LoopState state = new LoopState(new LoopRequest("t", "q"), 0, List.of(), List.of(), startedAtMillis);

        assertThat(policy.decide(state)).isEqualTo(LoopDecision.FORCE_FINALIZE);
    }

    @Test
    void repeatedRejectedFinalTurnsWithoutTools_stops() {
        LoopState state = stateWithHistory(List.of(rejectedNoTool(0), rejectedNoTool(1)));

        assertThat(policy.decide(state)).isEqualTo(LoopDecision.STOP);
    }

    @Test
    void rejectedTurnWithToolCallStillContinues() {
        LoopState state = stateWithHistory(List.of(
                rejectedNoTool(0),
                new LoopStep(1, "查詢", List.of("find_call_graph"), Verdict.revise("再查"))));

        assertThat(policy.decide(state)).isEqualTo(LoopDecision.CONTINUE);
    }

    @Test
    void acceptedOrInsufficientHistory_continues() {
        LoopState state = stateWithHistory(List.of(
                new LoopStep(0, "回答", List.of(), Verdict.accept())));

        assertThat(policy.decide(state)).isEqualTo(LoopDecision.CONTINUE);
    }

    private LoopState stateWithHistory(List<LoopStep> history) {
        return new LoopState(new LoopRequest("t", "q"), history.size(), List.of(), history, System.currentTimeMillis());
    }

    private LoopStep rejectedNoTool(int index) {
        return new LoopStep(index, "回答", List.of(), Verdict.revise("不夠"));
    }
}
