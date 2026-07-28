package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.delivery.DeliveryClaim;
import com.java.system.agent.inbox.domain.delivery.DeliveryFailure;
import com.java.system.agent.inbox.domain.delivery.DeliveryId;
import com.java.system.agent.inbox.domain.delivery.DeliveryKind;
import com.java.system.agent.inbox.domain.delivery.DeliveryMessage;
import com.java.system.agent.inbox.domain.delivery.DeliveryStatus;
import com.java.system.agent.inbox.port.out.DeliveryOutboxPort;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunResponseKind;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PostgreSQL delivery outbox 的單筆認領與 receipt 順序轉換 adapter
 */
public final class PostgresDeliveryOutboxAdapter implements DeliveryOutboxPort {

    private static final DeliveryFailure PREDECESSOR_BLOCKED = new DeliveryFailure(
            "PREDECESSOR_BLOCKED", "Receipt delivery was permanently blocked");

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public PostgresDeliveryOutboxAdapter(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbc client must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transaction template must not be null");
    }

    @Override
    public Optional<DeliveryClaim> claimNext(Instant now) {
        Objects.requireNonNull(now, "claim timestamp must not be null");
        return executeInTransaction(() -> {
            Optional<DeliveryMessage> pending = jdbcClient.sql("""
                    SELECT delivery_id, inbox_message_id, analysis_run_id, delivery_kind, response_kind, outcome,
                           source_type, source_key, participant_source_type, participant_key, response_text,
                           status, attempt_count, next_attempt_at, last_failure_category, last_failure_description,
                           provider_message_id, created_at, updated_at
                    FROM delivery_outbox
                    WHERE status = 'PENDING'
                       OR (status = 'RETRY_SCHEDULED' AND next_attempt_at <= :now)
                    ORDER BY next_attempt_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """)
                    .param("now", Timestamp.from(now))
                    .query(this::mapDeliveryMessage)
                    .optional();
            if (pending.isEmpty()) {
                return Optional.empty();
            }
            DeliveryMessage message = pending.get();
            int updatedRows = jdbcClient.sql("""
                    UPDATE delivery_outbox
                    SET status = 'PROCESSING', attempt_count = attempt_count + 1, updated_at = :now
                    WHERE delivery_id = :deliveryId
                      AND status IN ('PENDING', 'RETRY_SCHEDULED')
                    """)
                    .param("now", Timestamp.from(now))
                    .param("deliveryId", message.deliveryId().value())
                    .update();
            requireSingleAffectedRow(updatedRows);
            DeliveryMessage claimed = new DeliveryMessage(
                    message.deliveryId(), message.inboxMessageId(), message.runId(), message.kind(), message.responseKind(),
                    message.outcome(), message.sessionSource(), message.participant(), message.responseText(),
                    DeliveryStatus.PROCESSING, Math.incrementExact(message.attemptCount()), message.nextAttemptAt(),
                    message.lastFailure(), Optional.empty(), message.createdAt(), now);
            return Optional.of(new DeliveryClaim(claimed));
        });
    }

    @Override
    public void recordDelivered(DeliveryClaim claim, String providerMessageId, Instant deliveredAt) {
        Objects.requireNonNull(claim, "delivery claim must not be null");
        Objects.requireNonNull(providerMessageId, "provider message ID must not be null");
        Objects.requireNonNull(deliveredAt, "delivered timestamp must not be null");
        executeInTransactionWithoutResult(() -> {
            DeliveryMessage message = claim.message();
            int updatedRows = jdbcClient.sql("""
                    UPDATE delivery_outbox
                    SET status = 'DELIVERED', provider_message_id = :providerMessageId,
                        last_failure_category = NULL, last_failure_description = NULL, updated_at = :deliveredAt
                    WHERE delivery_id = :deliveryId
                      AND status = 'PROCESSING'
                      AND attempt_count = :attemptCount
                    """)
                    .param("providerMessageId", providerMessageId)
                    .param("deliveredAt", Timestamp.from(deliveredAt))
                    .param("deliveryId", message.deliveryId().value())
                    .param("attemptCount", message.attemptCount())
                    .update();
            requireSingleAffectedRow(updatedRows);
            if (message.kind() == DeliveryKind.RECEIPT) {
                promoteWaitingFinal(message.inboxMessageId(), deliveredAt);
            }
        });
    }

    @Override
    public void recordRetry(DeliveryClaim claim, DeliveryFailure failure, Instant retryAt, Instant updatedAt) {
        Objects.requireNonNull(claim, "delivery claim must not be null");
        Objects.requireNonNull(failure, "delivery failure must not be null");
        Objects.requireNonNull(retryAt, "retry timestamp must not be null");
        Objects.requireNonNull(updatedAt, "updated timestamp must not be null");
        transitionClaim(claim, DeliveryStatus.RETRY_SCHEDULED, Optional.of(failure), retryAt, updatedAt, Optional.empty());
    }

    @Override
    public void recordBlocked(DeliveryClaim claim, DeliveryFailure failure, Instant blockedAt) {
        Objects.requireNonNull(claim, "delivery claim must not be null");
        Objects.requireNonNull(failure, "delivery failure must not be null");
        Objects.requireNonNull(blockedAt, "blocked timestamp must not be null");
        executeInTransactionWithoutResult(() -> {
            updateClaim(claim, DeliveryStatus.BLOCKED, Optional.of(failure), blockedAt, blockedAt, Optional.empty());
            if (claim.message().kind() == DeliveryKind.RECEIPT) {
                blockWaitingFinal(claim.message().inboxMessageId(), blockedAt);
            }
        });
    }

    @Override
    public boolean recoverClaim(DeliveryClaim claim, Instant recoveredAt) {
        Objects.requireNonNull(claim, "delivery claim must not be null");
        Objects.requireNonNull(recoveredAt, "claim recovery timestamp must not be null");
        DeliveryMessage message = claim.message();
        return executeInTransaction(() -> jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = 'PENDING', next_attempt_at = :recoveredAt, updated_at = :recoveredAt
                WHERE delivery_id = :deliveryId
                  AND status = 'PROCESSING'
                  AND attempt_count = :attemptCount
                """)
                .param("recoveredAt", Timestamp.from(recoveredAt))
                .param("deliveryId", message.deliveryId().value())
                .param("attemptCount", message.attemptCount())
                .update() == 1);
    }

    @Override
    public int recoverInterrupted(Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recovery timestamp must not be null");
        return executeInTransaction(() -> jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = 'PENDING', next_attempt_at = :recoveredAt, updated_at = :recoveredAt
                WHERE status = 'PROCESSING'
                """)
                .param("recoveredAt", Timestamp.from(recoveredAt))
                .update());
    }

    private void transitionClaim(
            DeliveryClaim claim,
            DeliveryStatus status,
            Optional<DeliveryFailure> failure,
            Instant nextAttemptAt,
            Instant updatedAt,
            Optional<String> providerMessageId) {
        executeInTransactionWithoutResult(() -> updateClaim(
                claim, status, failure, nextAttemptAt, updatedAt, providerMessageId));
    }

    private void updateClaim(
            DeliveryClaim claim,
            DeliveryStatus status,
            Optional<DeliveryFailure> failure,
            Instant nextAttemptAt,
            Instant updatedAt,
            Optional<String> providerMessageId) {
        DeliveryMessage message = claim.message();
        int updatedRows = jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = :status, next_attempt_at = :nextAttemptAt,
                    last_failure_category = :failureCategory, last_failure_description = :failureDescription,
                    provider_message_id = :providerMessageId, updated_at = :updatedAt
                WHERE delivery_id = :deliveryId
                  AND status = 'PROCESSING'
                  AND attempt_count = :attemptCount
                """)
                .param("status", status.name())
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("failureCategory", failure.map(DeliveryFailure::category).orElse(null))
                .param("failureDescription", failure.map(DeliveryFailure::description).orElse(null))
                .param("providerMessageId", providerMessageId.orElse(null))
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("deliveryId", message.deliveryId().value())
                .param("attemptCount", message.attemptCount())
                .update();
        requireSingleAffectedRow(updatedRows);
    }

    private void promoteWaitingFinal(InboxMessageId inboxMessageId, Instant updatedAt) {
        jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = 'PENDING', updated_at = :updatedAt
                WHERE inbox_message_id = :inboxMessageId
                  AND delivery_kind = 'FINAL_RESPONSE'
                  AND status = 'WAITING_FOR_RECEIPT'
                """)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("inboxMessageId", inboxMessageId.value())
                .update();
    }

    private void blockWaitingFinal(InboxMessageId inboxMessageId, Instant blockedAt) {
        jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = 'BLOCKED', last_failure_category = :failureCategory,
                    last_failure_description = :failureDescription, updated_at = :blockedAt
                WHERE inbox_message_id = :inboxMessageId
                  AND delivery_kind = 'FINAL_RESPONSE'
                  AND status = 'WAITING_FOR_RECEIPT'
                """)
                .param("failureCategory", PREDECESSOR_BLOCKED.category())
                .param("failureDescription", PREDECESSOR_BLOCKED.description())
                .param("blockedAt", Timestamp.from(blockedAt))
                .param("inboxMessageId", inboxMessageId.value())
                .update();
    }

    private DeliveryMessage mapDeliveryMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        String responseKind = resultSet.getString("response_kind");
        String outcome = resultSet.getString("outcome");
        String failureCategory = resultSet.getString("last_failure_category");
        String failureDescription = resultSet.getString("last_failure_description");
        String providerMessageId = resultSet.getString("provider_message_id");
        return new DeliveryMessage(
                new DeliveryId(resultSet.getString("delivery_id")), new InboxMessageId(resultSet.getString("inbox_message_id")),
                new AnalysisRunId(resultSet.getString("analysis_run_id")), DeliveryKind.valueOf(resultSet.getString("delivery_kind")),
                Optional.ofNullable(responseKind).map(RunResponseKind::valueOf), Optional.ofNullable(outcome).map(RunOutcome::valueOf),
                new SessionSourceRef(resultSet.getString("source_type"), resultSet.getString("source_key")),
                new ParticipantRef(resultSet.getString("participant_source_type"), resultSet.getString("participant_key")),
                resultSet.getString("response_text"), DeliveryStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"), resultSet.getTimestamp("next_attempt_at").toInstant(),
                Optional.ofNullable(failureCategory).map(category -> new DeliveryFailure(category, failureDescription)),
                Optional.ofNullable(providerMessageId), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private <T> T executeInTransaction(Supplier<T> operation) {
        try {
            T result = transactionTemplate.execute(transactionStatus -> operation.get());
            return Objects.requireNonNull(result, "transaction result must not be null");
        } catch (InboxPersistenceConflictException exception) {
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private void executeInTransactionWithoutResult(Runnable operation) {
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> operation.run());
        } catch (InboxPersistenceConflictException exception) {
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private void requireSingleAffectedRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new InboxPersistenceConflictException();
        }
    }
}
