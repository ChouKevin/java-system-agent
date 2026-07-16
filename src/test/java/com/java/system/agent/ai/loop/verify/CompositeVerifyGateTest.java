package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.GateDecision;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeVerifyGateTest {

    private static LoopState state() {
        return LoopState.init(new LoopRequest("t", "q"));
    }

    @Test
    void recordsStableGateNamesAndStopsAtFirstRejection() {
        CompositeVerifyGate gate = new CompositeVerifyGate(List.of(
                new NamedVerifyGate("first", (candidate, loopState) -> Verdict.accept()),
                new NamedVerifyGate("second", (candidate, loopState) -> Verdict.revise("不完整")),
                new NamedVerifyGate("third", (candidate, loopState) -> Verdict.accept())));

        Verdict verdict = gate.verify(new Candidate("草稿"), state());

        assertThat(verdict.decisions()).extracting(GateDecision::gateName)
                .containsExactly("first", "second");
        assertThat(verdict.decisions()).extracting(GateDecision::accepted)
                .containsExactly(true, false);
        assertThat(verdict.critique()).isEqualTo("不完整");
    }

    @Test
    void acceptsWhenAllAccept() {
        CompositeVerifyGate gate = new CompositeVerifyGate(List.of(
                new NamedVerifyGate("first", (candidate, loopState) -> Verdict.accept()),
                new NamedVerifyGate("second", (candidate, loopState) -> Verdict.accept())));

        Verdict verdict = gate.verify(new Candidate("x"), state());

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.decisions()).extracting(GateDecision::gateName)
                .containsExactly("first", "second");
    }
}
