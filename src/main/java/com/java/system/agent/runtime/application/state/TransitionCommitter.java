package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AttemptState;
import com.java.system.agent.runtime.port.out.AnalysisTransitionPort;

import java.util.Objects;

/**
 * 讓事件真正生效的唯一路徑：算出候選狀態、落地事件、確認落地結果後才採用新狀態
 *
 * <p>由 lifecycle、planning 之後的 semantic、以及 loop 本身在扣預算或釘選 revision 時
 * 呼叫；先透過 {@link StateReducer#reduce} 算出候選 {@link StateTransition}，再把事件
 * 交給 {@code AnalysisTransitionPort} 持久化，只有在持久化成功且回傳的狀態與候選狀態
 * 完全相同時才把它當作新狀態採用——回傳的狀態與候選狀態不同一律視為提交失敗，
 * 拋出 {@link AnalysisTransitionCommitException}</p>
 */
public final class TransitionCommitter {

    private final StateReducer reducer;
    private final AnalysisTransitionPort<? super StateTransition> transitionPort;

    public TransitionCommitter(
            StateReducer reducer,
            AnalysisTransitionPort<? super StateTransition> transitionPort) {
        this.reducer = Objects.requireNonNull(reducer, "state reducer must not be null");
        this.transitionPort = Objects.requireNonNull(
                transitionPort, "analysis transition port must not be null");
    }

    public AttemptState apply(AttemptState currentState, AnalysisEvent event) {
        Objects.requireNonNull(currentState, "current analysis state must not be null");
        Objects.requireNonNull(event, "analysis event must not be null");
        StateTransition transition = Objects.requireNonNull(
                reducer.reduce(currentState, event), "state reducer must return a transition");
        try {
            AttemptState committedState = transitionPort.commit(transition);
            if (Objects.isNull(committedState)) {
                throw new AnalysisTransitionCommitException(
                        "analysis transition commit returned no candidate state");
            }
            if (!transition.candidateState().equals(committedState)) {
                throw new AnalysisTransitionCommitException(
                        "analysis transition commit returned a different candidate state");
            }
            return committedState;
        } catch (AnalysisTransitionCommitException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AnalysisTransitionCommitException(
                    "analysis transition could not be committed", exception);
        }
    }
}
