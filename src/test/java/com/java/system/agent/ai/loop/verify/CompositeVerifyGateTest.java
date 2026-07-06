package com.java.system.agent.ai.loop.verify;

import com.java.system.agent.ai.loop.Candidate;
import com.java.system.agent.ai.loop.LoopRequest;
import com.java.system.agent.ai.loop.LoopState;
import com.java.system.agent.ai.loop.Verdict;
import com.java.system.agent.ai.loop.VerifyGate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeVerifyGateTest {

    private static LoopState state() {
        return LoopState.init(new LoopRequest("t", "q"));
    }

    @Test
    void returnsFirstReviseAndSkipsLaterStages() {
        VerifyGate reject = (candidate, loopState) -> Verdict.revise("stage1");
        VerifyGate boom = (candidate, loopState) -> {
            throw new AssertionError("不該被呼叫");
        };

        Verdict verdict = new CompositeVerifyGate(List.of(reject, boom))
                .verify(new Candidate("x"), state());

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.critique()).isEqualTo("stage1");
    }

    @Test
    void acceptsWhenAllAccept() {
        CompositeVerifyGate gate = new CompositeVerifyGate(List.of(
                (candidate, loopState) -> Verdict.accept(),
                (candidate, loopState) -> Verdict.accept()));

        assertThat(gate.verify(new Candidate("x"), state()).accepted()).isTrue();
    }
}
