package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.port.out.AnalysisCancellationPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.Optional;

/**
 * 從 PostgreSQL authoritative run snapshot 唯讀 cancellation marker 的 JDBC adapter
 */
public final class PostgresAnalysisCancellationAdapter implements AnalysisCancellationPort {

    private final JdbcClient jdbcClient;

    public PostgresAnalysisCancellationAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbc client must not be null");
    }

    @Override
    public boolean isCancellationRequested(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        try {
            Optional<Boolean> cancellationRequested = jdbcClient.sql("""
                    SELECT cancellation_requested
                    FROM agent_run
                    WHERE run_id = :runId
                    """)
                    .param("runId", runId.value())
                    .query(Boolean.class)
                    .optional();
            return cancellationRequested.orElse(false);
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }
}
