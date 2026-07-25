package com.java.system.agent.runtime.application.state;

/**
 * 事件攜帶的 {@code expectedStateRevision} 與 attempt 目前的 {@code stateRevision}
 * 不一致時，由 {@link DefaultStateReducer#validateEnvelope} 拋出
 *
 * <p>是樂觀並行控制在偵測到寫入衝突時的訊號</p>
 */
public class StaleStateRevisionException extends IllegalArgumentException {

    public StaleStateRevisionException(long expectedRevision, long actualRevision) {
        super("expected state revision %d but current revision is %d"
                .formatted(expectedRevision, actualRevision));
    }
}
