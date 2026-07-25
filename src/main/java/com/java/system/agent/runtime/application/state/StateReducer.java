package com.java.system.agent.runtime.application.state;

import com.java.system.agent.runtime.domain.run.AttemptState;

/**
 * 把事件套用到目前狀態、算出候選新狀態的抽象
 *
 * <p>唯一的實作是 {@link DefaultStateReducer}；由
 * {@link TransitionCommitter} 呼叫，回傳值只是候選狀態，尚未落地</p>
 */
public interface StateReducer {

    StateTransition reduce(AttemptState currentState, AnalysisEvent event);
}
