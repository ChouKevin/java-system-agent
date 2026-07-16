package com.java.system.agent.slack.client;

/** 串流失敗時的善後回呼，由呼叫端決定如何釋放資源與通知使用者 */
@FunctionalInterface
public interface SlackStreamFailureHandler {

    /** 處理串流失敗事件 */
    void handle(SlackStreamFailure failure);
}
