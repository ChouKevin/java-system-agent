package com.java.system.agent.ai.trace;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

@DependsOnDatabaseInitialization
public class TracePartitionManager {

    private final JdbcClient jdbcClient;

    public TracePartitionManager(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @PostConstruct
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void initializePartitions() {
        Instant current = Instant.now();
        ZonedDateTime nextMonth = current.atZone(ZoneOffset.UTC).plusMonths(1);
        ensurePartition(current);
        ensurePartition(nextMonth.toInstant());
    }

    public void ensurePartition(Instant target) {
        Instant safeTarget = Objects.requireNonNull(target, "target must not be null");
        jdbcClient.sql("SELECT observability.ensure_agent_trace_partition(:target)")
                .param("target", Timestamp.from(safeTarget))
                .query(Object.class)
                .list();
    }
}
