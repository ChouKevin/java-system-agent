package com.java.system.agent.ai.loop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoopStateTest {

    @Test
    void init_capturesQueryAndStartsEmpty() {
        LoopState state = LoopState.init(new LoopRequest("thread-1", "如何計算獎金?"));

        assertThat(state.query()).isEqualTo("如何計算獎金?");
        assertThat(state.iteration()).isZero();
        assertThat(state.history()).isEmpty();
        assertThat(state.startedAtMillis()).isPositive();
    }

    @Test
    void recordStep_incrementsIterationImmutably() {
        LoopState state = LoopState.init(new LoopRequest("t", "q"));
        LoopStep step = new LoopStep(0, "第一輪", List.of("read_service_map"), null);

        LoopState next = state.recordStep(step);

        assertThat(state.iteration()).isZero();
        assertThat(next.iteration()).isEqualTo(1);
        assertThat(next.history()).containsExactly(step);
        assertThat(next.startedAtMillis()).isEqualTo(state.startedAtMillis());
    }

    @Test
    void injectCritique_appendsWithoutAdvancingIteration() {
        LoopState state = LoopState.init(new LoopRequest("t", "q"));

        LoopState next = state.injectCritique("證據不足");

        assertThat(next.iteration()).isZero();
        assertThat(next.critiques()).containsExactly("證據不足");
        assertThat(next.startedAtMillis()).isEqualTo(state.startedAtMillis());
    }
}
