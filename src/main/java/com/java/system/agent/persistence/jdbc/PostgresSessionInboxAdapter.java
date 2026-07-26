package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.inbox.domain.InboxEnqueueRequest;
import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.InboxMessageStatus;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.port.out.InboxIdentityGenerator;
import com.java.system.agent.inbox.port.out.SessionInboxPort;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
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
 * PostgreSQL session inbox 的原子 JDBC 持久化 adapter
 */
public final class PostgresSessionInboxAdapter implements SessionInboxPort {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final InboxIdentityGenerator identityGenerator;

    public PostgresSessionInboxAdapter(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            InboxIdentityGenerator identityGenerator) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbc client must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transaction template must not be null");
        this.identityGenerator = Objects.requireNonNull(identityGenerator, "inbox identity generator must not be null");
    }

    @Override
    public InboxMessage enqueue(InboxEnqueueRequest request) {
        Objects.requireNonNull(request, "inbox enqueue request must not be null");
        try {
            Optional<InboxMessage> existingMessage = findByDeduplicationKey(request);
            if (existingMessage.isPresent()) {
                return duplicateOrConflict(existingMessage.get(), request);
            }
            return executeInTransaction(() -> enqueueWithinTransaction(request));
        } catch (InboxPersistenceConflictException exception) {
            throw exception;
        } catch (JdbcPersistenceException exception) {
            return resolveUniqueRace(request, exception);
        }
    }

    @Override
    public Optional<InboxMessage> claimNext(Instant now) {
        Objects.requireNonNull(now, "claim timestamp must not be null");
        return executeInTransaction(() -> {
            Optional<InboxMessage> pendingHead = jdbcClient.sql("""
                    SELECT inbox.inbox_message_id, session.source_type, session.source_key, inbox.source_message_id,
                           inbox.session_id, inbox.session_sequence, inbox.analysis_run_id, inbox.exact_question,
                           inbox.status, inbox.attempt_count, inbox.available_at, inbox.claimed_at,
                           inbox.last_error_code, inbox.last_error_description
                    FROM session_inbox inbox
                    JOIN agent_session session ON session.session_id = inbox.session_id
                    WHERE inbox.status = 'PENDING'
                      AND inbox.available_at <= :now
                      AND NOT EXISTS (
                          SELECT 1
                          FROM session_inbox earlier
                          WHERE earlier.session_id = inbox.session_id
                            AND earlier.session_sequence < inbox.session_sequence
                            AND earlier.status IN ('PENDING', 'PROCESSING')
                      )
                    ORDER BY inbox.available_at, inbox.created_at, inbox.inbox_message_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """)
                    .param("now", Timestamp.from(now))
                    .query(this::mapInboxMessage)
                    .optional();
            if (pendingHead.isEmpty()) {
                return Optional.empty();
            }

            InboxMessage pendingMessage = pendingHead.get();
            int updatedRows = jdbcClient.sql("""
                    UPDATE session_inbox
                    SET status = 'PROCESSING',
                        attempt_count = attempt_count + 1,
                        claimed_at = :claimedAt,
                        updated_at = :updatedAt
                    WHERE inbox_message_id = :inboxMessageId
                      AND status = 'PENDING'
                    """)
                    .param("claimedAt", Timestamp.from(now))
                    .param("updatedAt", Timestamp.from(now))
                    .param("inboxMessageId", pendingMessage.inboxMessageId().value())
                    .update();
            requireSingleAffectedRow(updatedRows);
            return Optional.of(new InboxMessage(
                    pendingMessage.inboxMessageId(), pendingMessage.source(), pendingMessage.sourceMessageId(),
                    pendingMessage.sessionId(), pendingMessage.sessionSequence(), pendingMessage.runId(),
                    pendingMessage.exactQuestion(), InboxMessageStatus.PROCESSING, pendingMessage.attemptCount() + 1,
                    pendingMessage.availableAt(), Optional.of(now), pendingMessage.lastFailure()));
        });
    }

    @Override
    public void complete(InboxMessage claimedMessage, Instant completedAt) {
        Objects.requireNonNull(claimedMessage, "claimed inbox message must not be null");
        Objects.requireNonNull(completedAt, "completed timestamp must not be null");
        requireProcessingClaim(claimedMessage);
        executeInTransactionWithoutResult(() -> {
            int updatedRows = jdbcClient.sql("""
                    UPDATE session_inbox
                    SET status = 'COMPLETED',
                        claimed_at = NULL,
                        last_error_code = NULL,
                        last_error_description = NULL,
                        updated_at = :completedAt
                    WHERE inbox_message_id = :inboxMessageId
                      AND analysis_run_id = :runId
                      AND session_id = :sessionId
                      AND status = 'PROCESSING'
                      AND attempt_count = :attemptCount
                    """)
                    .param("completedAt", Timestamp.from(completedAt))
                    .param("inboxMessageId", claimedMessage.inboxMessageId().value())
                    .param("runId", claimedMessage.runId().value())
                    .param("sessionId", claimedMessage.sessionId().value())
                    .param("attemptCount", claimedMessage.attemptCount())
                    .update();
            requireSingleAffectedRow(updatedRows);
        });
    }

    @Override
    public void retry(InboxMessage claimedMessage, InboxFailure failure, Instant availableAt) {
        Objects.requireNonNull(claimedMessage, "claimed inbox message must not be null");
        Objects.requireNonNull(failure, "inbox failure must not be null");
        Objects.requireNonNull(availableAt, "available timestamp must not be null");
        requireProcessingClaim(claimedMessage);
        executeInTransactionWithoutResult(() -> {
            int updatedRows = jdbcClient.sql("""
                    UPDATE session_inbox
                    SET status = 'PENDING',
                        claimed_at = NULL,
                        available_at = :availableAt,
                        last_error_code = :failureCode,
                        last_error_description = :failureDescription,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE inbox_message_id = :inboxMessageId
                      AND analysis_run_id = :runId
                      AND session_id = :sessionId
                      AND status = 'PROCESSING'
                      AND attempt_count = :attemptCount
                    """)
                    .param("availableAt", Timestamp.from(availableAt))
                    .param("failureCode", failure.code())
                    .param("failureDescription", failure.description())
                    .param("inboxMessageId", claimedMessage.inboxMessageId().value())
                    .param("runId", claimedMessage.runId().value())
                    .param("sessionId", claimedMessage.sessionId().value())
                    .param("attemptCount", claimedMessage.attemptCount())
                    .update();
            requireSingleAffectedRow(updatedRows);
        });
    }

    @Override
    public void fail(InboxMessage claimedMessage, InboxFailure failure, Instant failedAt) {
        Objects.requireNonNull(claimedMessage, "claimed inbox message must not be null");
        Objects.requireNonNull(failure, "inbox failure must not be null");
        Objects.requireNonNull(failedAt, "failed timestamp must not be null");
        requireProcessingClaim(claimedMessage);
        executeInTransactionWithoutResult(() -> {
            int updatedRows = jdbcClient.sql("""
                    UPDATE session_inbox
                    SET status = 'FAILED',
                        claimed_at = NULL,
                        last_error_code = :failureCode,
                        last_error_description = :failureDescription,
                        updated_at = :failedAt
                    WHERE inbox_message_id = :inboxMessageId
                      AND analysis_run_id = :runId
                      AND session_id = :sessionId
                      AND status = 'PROCESSING'
                      AND attempt_count = :attemptCount
                    """)
                    .param("failedAt", Timestamp.from(failedAt))
                    .param("failureCode", failure.code())
                    .param("failureDescription", failure.description())
                    .param("inboxMessageId", claimedMessage.inboxMessageId().value())
                    .param("runId", claimedMessage.runId().value())
                    .param("sessionId", claimedMessage.sessionId().value())
                    .param("attemptCount", claimedMessage.attemptCount())
                    .update();
            requireSingleAffectedRow(updatedRows);
        });
    }

    @Override
    public int recoverInterrupted(Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recovery timestamp must not be null");
        return executeInTransaction(() -> jdbcClient.sql("""
                UPDATE session_inbox
                SET status = 'PENDING',
                    claimed_at = NULL,
                    available_at = :recoveredAt,
                    updated_at = :recoveredAt
                WHERE status = 'PROCESSING'
                """)
                .param("recoveredAt", Timestamp.from(recoveredAt))
                .update());
    }

    private InboxMessage enqueueWithinTransaction(InboxEnqueueRequest request) {
        Optional<InboxMessage> existingMessage = findByDeduplicationKey(request);
        if (existingMessage.isPresent()) {
            return duplicateOrConflict(existingMessage.get(), request);
        }

        SessionId sessionId = resolveSessionId(request.source());
        Long allocatedSequence = jdbcClient.sql("""
                UPDATE agent_session
                SET next_inbox_sequence = next_inbox_sequence + 1
                WHERE session_id = :sessionId
                RETURNING next_inbox_sequence - 1
                """)
                .param("sessionId", sessionId.value())
                .query(Long.class)
                .single();
        long sessionSequence = Objects.requireNonNull(allocatedSequence, "allocated inbox sequence must not be null");
        InboxMessageId inboxMessageId = identityGenerator.nextInboxMessageId();
        AnalysisRunId runId = identityGenerator.nextRunId();
        int insertedRows = jdbcClient.sql("""
                INSERT INTO session_inbox (
                    inbox_message_id, source_type, source_message_id, session_id, session_sequence, analysis_run_id,
                    exact_question, status, attempt_count, available_at, claimed_at, last_error_code,
                    last_error_description, created_at, updated_at
                ) VALUES (
                    :inboxMessageId, :sourceType, :sourceMessageId, :sessionId, :sessionSequence, :runId,
                    :exactQuestion, 'PENDING', 0, CURRENT_TIMESTAMP, NULL, NULL, NULL, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .param("sourceType", request.source().sourceType())
                .param("sourceMessageId", request.sourceMessageId().value())
                .param("sessionId", sessionId.value())
                .param("sessionSequence", sessionSequence)
                .param("runId", runId.value())
                .param("exactQuestion", request.exactQuestion())
                .update();
        requireSingleAffectedRow(insertedRows);
        return findByInboxMessageId(inboxMessageId)
                .orElseThrow(JdbcPersistenceException::new);
    }

    private SessionId resolveSessionId(SessionSourceRef source) {
        Optional<String> existingSessionId = jdbcClient.sql("""
                SELECT session_id
                FROM agent_session
                WHERE source_type = :sourceType
                  AND source_key = :sourceKey
                """)
                .param("sourceType", source.sourceType())
                .param("sourceKey", source.sourceKey())
                .query(String.class)
                .optional();
        if (existingSessionId.isPresent()) {
            return new SessionId(existingSessionId.get());
        }

        SessionId sessionId = identityGenerator.nextSessionId();
        int insertedRows = jdbcClient.sql("""
                INSERT INTO agent_session (
                    session_id, source_type, source_key, next_inbox_sequence, next_turn_sequence, created_at
                ) VALUES (
                    :sessionId, :sourceType, :sourceKey, 0, 0, CURRENT_TIMESTAMP
                )
                """)
                .param("sessionId", sessionId.value())
                .param("sourceType", source.sourceType())
                .param("sourceKey", source.sourceKey())
                .update();
        requireSingleAffectedRow(insertedRows);
        return sessionId;
    }

    private InboxMessage resolveUniqueRace(InboxEnqueueRequest request, JdbcPersistenceException originalFailure) {
        try {
            Optional<InboxMessage> existingMessage = findByDeduplicationKey(request);
            if (existingMessage.isPresent()) {
                return duplicateOrConflict(existingMessage.get(), request);
            }
            return executeInTransaction(() -> enqueueWithinTransaction(request));
        } catch (InboxPersistenceConflictException exception) {
            throw exception;
        } catch (JdbcPersistenceException exception) {
            throw originalFailure;
        }
    }

    private InboxMessage duplicateOrConflict(InboxMessage existingMessage, InboxEnqueueRequest request) {
        if (existingMessage.source().equals(request.source())
                && existingMessage.exactQuestion().equals(request.exactQuestion())) {
            return existingMessage;
        }
        throw new InboxPersistenceConflictException();
    }

    private Optional<InboxMessage> findByDeduplicationKey(InboxEnqueueRequest request) {
        try {
            return jdbcClient.sql("""
                    SELECT inbox.inbox_message_id, session.source_type, session.source_key, inbox.source_message_id,
                           inbox.session_id, inbox.session_sequence, inbox.analysis_run_id, inbox.exact_question,
                           inbox.status, inbox.attempt_count, inbox.available_at, inbox.claimed_at,
                           inbox.last_error_code, inbox.last_error_description
                    FROM session_inbox inbox
                    JOIN agent_session session ON session.session_id = inbox.session_id
                    WHERE inbox.source_type = :sourceType
                      AND inbox.source_message_id = :sourceMessageId
                    """)
                    .param("sourceType", request.source().sourceType())
                    .param("sourceMessageId", request.sourceMessageId().value())
                    .query(this::mapInboxMessage)
                    .optional();
        } catch (DataAccessException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private Optional<InboxMessage> findByInboxMessageId(InboxMessageId inboxMessageId) {
        return jdbcClient.sql("""
                SELECT inbox.inbox_message_id, session.source_type, session.source_key, inbox.source_message_id,
                       inbox.session_id, inbox.session_sequence, inbox.analysis_run_id, inbox.exact_question,
                       inbox.status, inbox.attempt_count, inbox.available_at, inbox.claimed_at,
                       inbox.last_error_code, inbox.last_error_description
                FROM session_inbox inbox
                JOIN agent_session session ON session.session_id = inbox.session_id
                WHERE inbox.inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .query(this::mapInboxMessage)
                .optional();
    }

    private InboxMessage mapInboxMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        String failureCode = resultSet.getString("last_error_code");
        String failureDescription = resultSet.getString("last_error_description");
        Optional<InboxFailure> lastFailure = Optional.ofNullable(failureCode)
                .map(code -> new InboxFailure(code, failureDescription));
        Optional<Instant> claimedAt = Optional.ofNullable(resultSet.getTimestamp("claimed_at"))
                .map(Timestamp::toInstant);
        return new InboxMessage(
                new InboxMessageId(resultSet.getString("inbox_message_id")),
                new SessionSourceRef(resultSet.getString("source_type"), resultSet.getString("source_key")),
                new SourceMessageId(resultSet.getString("source_message_id")),
                new SessionId(resultSet.getString("session_id")),
                resultSet.getLong("session_sequence"),
                new AnalysisRunId(resultSet.getString("analysis_run_id")),
                resultSet.getString("exact_question"),
                InboxMessageStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("available_at").toInstant(),
                claimedAt,
                lastFailure);
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

    private void requireProcessingClaim(InboxMessage claimedMessage) {
        if (claimedMessage.status() != InboxMessageStatus.PROCESSING || claimedMessage.claimedAt().isEmpty()) {
            throw new InboxPersistenceConflictException();
        }
    }
}
