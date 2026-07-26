package com.java.system.agent.persistence.jdbc;

/**
 * JDBC durable persistence 邊界可安全對外傳遞的失敗
 */
public class JdbcPersistenceException extends RuntimeException {

    public JdbcPersistenceException() {
        this("durable persistence operation failed");
    }

    protected JdbcPersistenceException(String description) {
        super(description);
    }
}
