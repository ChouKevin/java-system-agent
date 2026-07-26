package com.java.system.agent.persistence.jdbc;

/**
 * Durable inbox 操作與目前已持久化狀態衝突
 */
public final class InboxPersistenceConflictException extends JdbcPersistenceException {

    public InboxPersistenceConflictException() {
        super("durable inbox persistence conflict");
    }
}
