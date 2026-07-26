package com.java.system.agent.inbox.port.in;

import com.java.system.agent.inbox.domain.InboxMessage;

/**
 * 將 session 來源訊息原子寫入 durable inbox 的公開入口
 */
public interface EnqueueSessionMessageUseCase {

    InboxMessage enqueue(EnqueueSessionMessageCommand command);
}
