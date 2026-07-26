package com.java.system.agent.persistence.jdbc;

/**
 * Durable session turn 操作與既有不可變內容衝突
 */
public final class SessionTurnConflictException extends JdbcPersistenceException {

    public SessionTurnConflictException() {
        super("durable session turn persistence conflict");
    }
}
