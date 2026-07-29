package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.port.out.AgentOperationsPort;
import com.java.system.agent.interaction.port.out.DurableAgentOperationsSnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 以 PostgreSQL durable inbox 與 delivery rows 計算指定觀測時間 queue 年齡的唯讀 adapter
 */
public final class PostgresAgentOperationsAdapter implements AgentOperationsPort {

    private final JdbcClient jdbcClient;

    public PostgresAgentOperationsAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "JDBC client must not be null");
    }

    @Override
    public DurableAgentOperationsSnapshot readDurableOperations(Instant observedAt) {
        Objects.requireNonNull(observedAt, "operations observation time must not be null");
        try {
            Optional<Duration> inboxAge = jdbcClient.sql("""
                    SELECT MIN(created_at)
                    FROM session_inbox candidate
                    WHERE candidate.status = 'PENDING'
                      AND candidate.available_at <= :observedAt
                      AND NOT EXISTS (
                          SELECT 1
                          FROM session_inbox processing
                          WHERE processing.status = 'PROCESSING'
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM session_inbox earlier
                          WHERE earlier.session_id = candidate.session_id
                            AND earlier.session_sequence < candidate.session_sequence
                            AND earlier.status IN ('PENDING', 'PROCESSING')
                      )
                    """)
                    .param("observedAt", Timestamp.from(observedAt))
                    .query(Timestamp.class)
                    .optional()
                    .map(timestamp -> timestamp.toInstant())
                    .map(createdAt -> ageAt(createdAt, observedAt));
            Map<DeliveryStatus, Optional<Duration>> deliveryAges = deliveryAges(observedAt);
            return new DurableAgentOperationsSnapshot(observedAt, inboxAge, deliveryAges);
        } catch (DataAccessException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private Map<DeliveryStatus, Optional<Duration>> deliveryAges(Instant observedAt) {
        Map<DeliveryStatus, Optional<Duration>> ages = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus status : DeliveryStatus.values()) {
            ages.put(status, Optional.empty());
        }
        jdbcClient.sql("""
                SELECT status, MIN(created_at) AS oldest_created_at
                FROM delivery_outbox
                GROUP BY status
                """)
                .query((resultSet, rowNumber) -> new DeliveryAgeRow(
                        DeliveryStatus.valueOf(resultSet.getString("status")),
                        resultSet.getTimestamp("oldest_created_at").toInstant()))
                .list()
                .forEach(row -> ages.put(row.status(), Optional.of(ageAt(row.createdAt(), observedAt))));
        return ages;
    }

    private static Duration ageAt(Instant createdAt, Instant observedAt) {
        Duration age = Duration.between(createdAt, observedAt);
        return age.isNegative() ? Duration.ZERO : age;
    }

    /**
     * delivery group aggregate 的 JDBC row mapping 值
     */
    private record DeliveryAgeRow(DeliveryStatus status, Instant createdAt) {
    }
}
