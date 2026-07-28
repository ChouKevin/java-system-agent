package com.java.system.agent.inbox.port.in;

import com.java.system.agent.inbox.domain.NormalizedSourceEvent;
import com.java.system.agent.inbox.domain.SourceAcceptance;

/**
 * 將正規化來源事件直接 durable admission 的公開入口
 */
public interface AcceptSourceEventUseCase {

    SourceAcceptance accept(NormalizedSourceEvent event);
}
