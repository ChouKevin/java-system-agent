package com.java.system.agent.runtime.port.out;

import com.java.system.agent.runtime.domain.run.AgentRunState;
import com.java.system.agent.runtime.domain.run.AgentBootstrap;
import com.java.system.agent.runtime.domain.run.AgentTransition;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;

import java.util.Optional;

/**
 * 持久化 Agent Run append-only transition 的原子外部邊界
 *
 * <p>首次 {@code RunStarted} 必須以 run ID 原子唯一建立，後續提交必須原子比較
 * persisted revision 與 event expected revision。成功時 append event 並發布完全相同的 candidate state；
 * 重複建立、重複 revision 或 stale revision 必須拋出 {@link AgentTransitionConflictException}</p>
 */
public interface AgentTransitionPort {

    /**
     * 原子唯一建立 run、依序 append bootstrap 三個 event，並只發布最後的 context state
     *
     * <p>conflict 或任何持久化失敗時，三個 event 與 candidate state 都不得部分可見</p>
     */
    AgentRunState bootstrap(AgentBootstrap bootstrap);

    /**
     * bootstrap 完成後以 revision CAS 提交單一 transition
     *
     * <p>不得用這個方法提交 {@code RunStarted} 或首次 {@code AttemptStarted}</p>
     */
    AgentRunState commit(AgentTransition transition);

    /**
     * 在同一個原子邊界確認 durable cancellation marker 並提交 terminal accepted transition
     *
     * <p>cancellation 已勝出時不得 append accepted event 或發布 candidate state，且必須拋出
     * {@link TerminalAcceptanceCancelledException}</p>
     */
    AgentRunState commitTerminalAcceptance(AgentTransition transition);

    /**
     * 唯讀取得 run 已持久化的 authoritative 狀態，找不到時回傳 empty
     */
    Optional<AgentRunState> findByRunId(AnalysisRunId runId);
}
