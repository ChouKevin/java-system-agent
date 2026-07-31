package com.java.system.agent.answering.port.out;

import com.java.system.agent.answering.domain.action.ExecuteAction;

/**
 * 受信任 adapter 執行已驗證 HTTP mutation intent 的 outbound port
 */
@FunctionalInterface
public interface HttpMutationPort {

    HttpMutationResult execute(ExecuteAction action);
}
