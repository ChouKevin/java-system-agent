package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AttemptState;

import java.util.Objects;

/**
 * {@link StateReducer#reduce} 的回傳值：觸發這次變化的事件與尚未落地的候選狀態
 *
 * <p>由 {@link TransitionCommitter} 消費：先把 {@code event} 持久化，成功後才把
 * {@code candidateState} 當作正式狀態採用</p>
 */
public record StateTransition(AnalysisEvent event, AttemptState candidateState) {

    public StateTransition {
        Objects.requireNonNull(event, "analysis event must not be null");
        Objects.requireNonNull(candidateState, "candidate state must not be null");
    }
}
