package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.interaction.domain.InboxClaim;
import com.java.system.agent.interaction.domain.InboxDeferReason;
import com.java.system.agent.interaction.domain.InboxFailure;
import com.java.system.agent.interaction.domain.FinalInteractionResponse;
import com.java.system.agent.interaction.domain.InboxMessage;
import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.InboxMessageStatus;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.delivery.DeliveryFailure;
import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.interaction.port.out.InboxIdentityGenerator;
import com.java.system.agent.interaction.port.out.SessionInboxPort;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunOutcome;
import com.java.system.agent.answering.domain.run.RunResponseKind;
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
 * PostgreSQL session inbox 與 terminal delivery 的原子 JDBC 持久化 adapter
 */
public final class PostgresSessionInboxAdapter implements SessionInboxPort {

    private static final DeliveryFailure PREDECESSOR_BLOCKED = new DeliveryFailure(
            "PREDECESSOR_BLOCKED", "Receipt delivery was permanently blocked");

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
    public Optional<InboxClaim> claimNext(Instant now) {
        Objects.requireNonNull(now, "claim timestamp must not be null");
        return executeInTransaction(() -> {
            jdbcClient.sql("SELECT pg_advisory_xact_lock(743211)")
                    .query((resultSet, rowNumber) -> Boolean.TRUE)
                    .single();
            Optional<InboxMessage> pendingHead = jdbcClient.sql("""
                    SELECT inbox.inbox_message_id, session.source_type, session.source_key, inbox.source_message_id,
                           inbox.session_id, inbox.session_sequence, inbox.analysis_run_id,
                           inbox.participant_source_type, inbox.participant_key, inbox.source_text, inbox.question_text,
                           inbox.status, inbox.attempt_count, inbox.available_at, inbox.claimed_at, inbox.defer_reason,
                           inbox.last_error_code, inbox.last_error_description
                    FROM session_inbox inbox
                    JOIN agent_session session ON session.session_id = inbox.session_id
                    WHERE inbox.status = 'PENDING'
                      AND inbox.available_at <= :now
                      AND NOT EXISTS (
                          SELECT 1
                          FROM session_inbox processing
                          WHERE processing.status = 'PROCESSING'
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM session_inbox earlier
                          WHERE earlier.session_id = inbox.session_id
                            AND earlier.session_sequence < inbox.session_sequence
                            AND earlier.status IN ('PENDING', 'PROCESSING')
                      )
                    ORDER BY inbox.available_at, inbox.created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """)
                    .param("now", Timestamp.from(now))
                    .query(this::mapInboxMessage)
                    .optional();
            if (pendingHead.isEmpty()) {
                return Optional.empty();
            }
            InboxMessage message = pendingHead.get();
            int updatedRows = jdbcClient.sql("""
                    UPDATE session_inbox
                    SET status = 'PROCESSING',
                        attempt_count = attempt_count + CASE WHEN defer_reason = 'MODEL_CAPACITY' THEN 0 ELSE 1 END,
                        claimed_at = :claimedAt,
                        defer_reason = CASE WHEN defer_reason = 'MODEL_CAPACITY' THEN defer_reason ELSE NULL END,
                        updated_at = :claimedAt
                    WHERE inbox_message_id = :inboxMessageId
                      AND status = 'PENDING'
                    """)
                    .param("claimedAt", Timestamp.from(now))
                    .param("inboxMessageId", message.inboxMessageId().value())
                    .update();
            requireSingleAffectedRow(updatedRows);
            int attemptCount = message.deferReason().isPresent()
                    ? message.attemptCount()
                    : Math.incrementExact(message.attemptCount());
            InboxMessage claimed = new InboxMessage(
                    message.inboxMessageId(), message.source(), message.sourceMessageId(), message.sessionId(),
                    message.sessionSequence(), message.runId(), message.participant(), message.sourceText(), message.questionText(),
                    InboxMessageStatus.PROCESSING, attemptCount, message.availableAt(), Optional.of(now),
                    message.deferReason(), message.lastFailure());
            return Optional.of(new InboxClaim(claimed));
        });
    }

    @Override
    public void completeWithFinal(InboxClaim claim, FinalInteractionResponse result, Instant completedAt) {
        Objects.requireNonNull(claim, "inbox claim must not be null");
        Objects.requireNonNull(result, "final interaction response must not be null");
        Objects.requireNonNull(completedAt, "completed timestamp must not be null");
        requireResultRun(claim, result);
        executeInTransactionWithoutResult(() -> {
            updateTerminalInbox(claim, InboxMessageStatus.COMPLETED, Optional.empty(), completedAt);
            insertFinalResponse(claim.message(), result.responseKind(), result.outcome(), result.responseText(), completedAt);
        });
    }

    @Override
    public void failWithFinal(InboxClaim claim, InboxFailure failure, String safeResponseText, Instant failedAt) {
        Objects.requireNonNull(claim, "inbox claim must not be null");
        Objects.requireNonNull(failure, "inbox failure must not be null");
        Objects.requireNonNull(safeResponseText, "safe response text must not be null");
        Objects.requireNonNull(failedAt, "failed timestamp must not be null");
        if (safeResponseText.isBlank()) {
            throw new IllegalArgumentException("safe response text must not be blank");
        }
        executeInTransactionWithoutResult(() -> {
            updateTerminalInbox(claim, InboxMessageStatus.FAILED, Optional.of(failure), failedAt);
            insertFinalResponse(claim.message(), RunResponseKind.RUNTIME_NOTICE, RunOutcome.FAILED, safeResponseText, failedAt);
        });
    }

    @Override
    public void retry(InboxClaim claim, InboxFailure failure, Instant availableAt) {
        Objects.requireNonNull(claim, "inbox claim must not be null");
        Objects.requireNonNull(failure, "inbox failure must not be null");
        Objects.requireNonNull(availableAt, "available timestamp must not be null");
        executeInTransactionWithoutResult(() -> updatePendingInbox(claim, failure, availableAt, Optional.empty()));
    }

    @Override
    public void deferForCapacity(InboxClaim claim, Instant retryAt) {
        Objects.requireNonNull(claim, "inbox claim must not be null");
        Objects.requireNonNull(retryAt, "capacity retry timestamp must not be null");
        executeInTransactionWithoutResult(() -> updatePendingInbox(
                claim, null, retryAt, Optional.of(InboxDeferReason.MODEL_CAPACITY)));
    }

    @Override
    public boolean recoverClaim(InboxClaim claim, Instant recoveredAt) {
        Objects.requireNonNull(claim, "inbox claim must not be null");
        Objects.requireNonNull(recoveredAt, "claim recovery timestamp must not be null");
        InboxMessage message = claim.message();
        requireProcessingClaim(claim);
        return executeInTransaction(() -> jdbcClient.sql("""
                UPDATE session_inbox
                SET status = 'PENDING', claimed_at = NULL, available_at = :recoveredAt, updated_at = :recoveredAt
                WHERE inbox_message_id = :inboxMessageId
                  AND analysis_run_id = :runId
                  AND session_id = :sessionId
                  AND status = 'PROCESSING'
                  AND attempt_count = :attemptCount
                  AND claimed_at = :claimedAt
                """)
                .param("recoveredAt", Timestamp.from(recoveredAt))
                .param("inboxMessageId", message.inboxMessageId().value())
                .param("runId", message.runId().value())
                .param("sessionId", message.sessionId().value())
                .param("attemptCount", message.attemptCount())
                .param("claimedAt", Timestamp.from(message.claimedAt().orElseThrow()))
                .update() == 1);
    }

    @Override
    public int recoverInterrupted(Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recovery timestamp must not be null");
        return executeInTransaction(() -> jdbcClient.sql("""
                UPDATE session_inbox
                SET status = 'PENDING', claimed_at = NULL, available_at = :recoveredAt, updated_at = :recoveredAt
                WHERE status = 'PROCESSING'
                """)
                .param("recoveredAt", Timestamp.from(recoveredAt))
                .update());
    }

    private void updateTerminalInbox(
            InboxClaim claim,
            InboxMessageStatus status,
            Optional<InboxFailure> failure,
            Instant transitionedAt) {
        InboxMessage message = claim.message();
        requireProcessingClaim(claim);
        int updatedRows = jdbcClient.sql("""
                UPDATE session_inbox
                SET status = :status, claimed_at = NULL, defer_reason = NULL,
                    last_error_code = :failureCode, last_error_description = :failureDescription, updated_at = :transitionedAt
                WHERE inbox_message_id = :inboxMessageId
                  AND analysis_run_id = :runId
                  AND session_id = :sessionId
                  AND status = 'PROCESSING'
                  AND attempt_count = :attemptCount
                  AND claimed_at = :claimedAt
                """)
                .param("status", status.name())
                .param("failureCode", failure.map(inboxFailure -> inboxFailure.code()).orElse(null))
                .param("failureDescription", failure.map(inboxFailure -> inboxFailure.description()).orElse(null))
                .param("transitionedAt", Timestamp.from(transitionedAt))
                .param("inboxMessageId", message.inboxMessageId().value())
                .param("runId", message.runId().value())
                .param("sessionId", message.sessionId().value())
                .param("attemptCount", message.attemptCount())
                .param("claimedAt", Timestamp.from(message.claimedAt().orElseThrow()))
                .update();
        requireSingleAffectedRow(updatedRows);
    }

    private void updatePendingInbox(
            InboxClaim claim,
            InboxFailure failure,
            Instant availableAt,
            Optional<InboxDeferReason> deferReason) {
        InboxMessage message = claim.message();
        requireProcessingClaim(claim);
        int updatedRows = jdbcClient.sql("""
                UPDATE session_inbox
                SET status = 'PENDING', claimed_at = NULL, available_at = :availableAt,
                    defer_reason = :deferReason, last_error_code = :failureCode,
                    last_error_description = :failureDescription, updated_at = :availableAt
                WHERE inbox_message_id = :inboxMessageId
                  AND analysis_run_id = :runId
                  AND session_id = :sessionId
                  AND status = 'PROCESSING'
                  AND attempt_count = :attemptCount
                  AND claimed_at = :claimedAt
                """)
                .param("availableAt", Timestamp.from(availableAt))
                .param("deferReason", deferReason.map(enumValue -> enumValue.name()).orElse(null))
                .param("failureCode", Optional.ofNullable(failure).map(inboxFailure -> inboxFailure.code()).orElse(null))
                .param("failureDescription", Optional.ofNullable(failure).map(inboxFailure -> inboxFailure.description()).orElse(null))
                .param("inboxMessageId", message.inboxMessageId().value())
                .param("runId", message.runId().value())
                .param("sessionId", message.sessionId().value())
                .param("attemptCount", message.attemptCount())
                .param("claimedAt", Timestamp.from(message.claimedAt().orElseThrow()))
                .update();
        requireSingleAffectedRow(updatedRows);
    }

    private void insertFinalResponse(
            InboxMessage message,
            RunResponseKind responseKind,
            RunOutcome outcome,
            String responseText,
            Instant createdAt) {
        ReceiptState receipt = lockReceipt(message.inboxMessageId());
        FinalState finalState = finalState(receipt.status());
        int insertedRows = jdbcClient.sql("""
                INSERT INTO delivery_outbox (
                    delivery_id, inbox_message_id, analysis_run_id, delivery_kind, response_kind, outcome,
                    source_type, source_key, participant_source_type, participant_key, response_text,
                    status, attempt_count, next_attempt_at, last_failure_category, last_failure_description,
                    provider_message_id, created_at, updated_at
                ) VALUES (
                    :deliveryId, :inboxMessageId, :runId, 'FINAL_RESPONSE', :responseKind, :outcome,
                    :sourceType, :sourceKey, :participantSourceType, :participantKey, :responseText,
                    :status, 0, :createdAt, :failureCategory, :failureDescription,
                    NULL, :createdAt, :createdAt
                )
                """)
                .param("deliveryId", identityGenerator.nextDeliveryId())
                .param("inboxMessageId", message.inboxMessageId().value())
                .param("runId", message.runId().value())
                .param("responseKind", responseKind.name())
                .param("outcome", outcome.name())
                .param("sourceType", message.source().sourceType())
                .param("sourceKey", message.source().sourceKey())
                .param("participantSourceType", message.participant().sourceType())
                .param("participantKey", message.participant().participantKey())
                .param("responseText", responseText)
                .param("status", finalState.status().name())
                .param("createdAt", Timestamp.from(createdAt))
                .param("failureCategory", finalState.failure().map(deliveryFailure -> deliveryFailure.category()).orElse(null))
                .param("failureDescription", finalState.failure().map(deliveryFailure -> deliveryFailure.description()).orElse(null))
                .update();
        requireSingleAffectedRow(insertedRows);
    }

    private ReceiptState lockReceipt(InboxMessageId inboxMessageId) {
        Optional<ReceiptState> receipt = jdbcClient.sql("""
                SELECT status
                FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId
                  AND delivery_kind = 'RECEIPT'
                FOR UPDATE
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .query((resultSet, rowNumber) -> new ReceiptState(
                        DeliveryStatus.valueOf(resultSet.getString("status"))))
                .optional();
        return receipt.orElseThrow(InboxPersistenceConflictException::new);
    }

    private FinalState finalState(DeliveryStatus receiptStatus) {
        return switch (receiptStatus) {
            case DELIVERED -> new FinalState(DeliveryStatus.PENDING, Optional.empty());
            case PENDING, PROCESSING, RETRY_SCHEDULED -> new FinalState(DeliveryStatus.WAITING_FOR_RECEIPT, Optional.empty());
            case BLOCKED -> new FinalState(DeliveryStatus.BLOCKED, Optional.of(PREDECESSOR_BLOCKED));
            case WAITING_FOR_RECEIPT -> throw new InboxPersistenceConflictException();
        };
    }

    private InboxMessage mapInboxMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        String failureCode = resultSet.getString("last_error_code");
        String failureDescription = resultSet.getString("last_error_description");
        String deferReason = resultSet.getString("defer_reason");
        Optional<InboxFailure> lastFailure = Optional.ofNullable(failureCode)
                .map(code -> new InboxFailure(code, failureDescription));
        Optional<Instant> claimedAt = Optional.ofNullable(resultSet.getTimestamp("claimed_at")).map(timestamp -> timestamp.toInstant());
        return new InboxMessage(
                new InboxMessageId(resultSet.getString("inbox_message_id")),
                new SessionSourceRef(resultSet.getString("source_type"), resultSet.getString("source_key")),
                new SourceMessageId(resultSet.getString("source_message_id")),
                new SessionId(resultSet.getString("session_id")), resultSet.getLong("session_sequence"),
                new AnalysisRunId(resultSet.getString("analysis_run_id")),
                new ParticipantRef(resultSet.getString("participant_source_type"), resultSet.getString("participant_key")),
                resultSet.getString("source_text"), resultSet.getString("question_text"),
                InboxMessageStatus.valueOf(resultSet.getString("status")), resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("available_at").toInstant(), claimedAt,
                Optional.ofNullable(deferReason).map(InboxDeferReason::valueOf), lastFailure);
    }

    private void requireResultRun(InboxClaim claim, FinalInteractionResponse result) {
        if (!claim.message().runId().equals(result.runId())) {
            throw new InboxPersistenceConflictException();
        }
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

    private void requireProcessingClaim(InboxClaim claim) {
        InboxMessage message = claim.message();
        if (message.status() != InboxMessageStatus.PROCESSING || message.claimedAt().isEmpty()) {
            throw new InboxPersistenceConflictException();
        }
    }

    private record ReceiptState(DeliveryStatus status) {
    }

    private record FinalState(DeliveryStatus status, Optional<DeliveryFailure> failure) {
    }
}
