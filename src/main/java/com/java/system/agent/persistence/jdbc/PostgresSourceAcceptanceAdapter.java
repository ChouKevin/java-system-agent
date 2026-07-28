package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.NormalizedSourceEvent;
import com.java.system.agent.inbox.domain.SourceAcceptance;
import com.java.system.agent.inbox.domain.SourceAcceptanceStatus;
import com.java.system.agent.inbox.domain.SourceAdmission;
import com.java.system.agent.inbox.domain.SourceEventConflictScope;
import com.java.system.agent.inbox.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.inbox.port.out.InboxIdentityGenerator;
import com.java.system.agent.inbox.port.out.SourceAcceptancePort;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL 中依固定鎖定順序執行來源事件原子 admission 的 JDBC adapter
 */
public final class PostgresSourceAcceptanceAdapter implements SourceAcceptancePort {

    private static final String CONFLICT_CATEGORY = "SOURCE_PAYLOAD_CONFLICT";

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final InboxIdentityGenerator identityGenerator;

    public PostgresSourceAcceptanceAdapter(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            InboxIdentityGenerator identityGenerator) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbc client must not be null");
        this.transactionTemplate = dedicatedReadCommittedTemplate(
                Objects.requireNonNull(transactionTemplate, "transaction template must not be null"));
        this.identityGenerator = Objects.requireNonNull(identityGenerator, "inbox identity generator must not be null");
    }

    @Override
    public SourceAcceptance accept(NormalizedSourceEvent event) {
        Objects.requireNonNull(event, "normalized source event must not be null");
        try {
            SourceAcceptance acceptance = transactionTemplate.execute(transactionStatus -> acceptWithinTransaction(event));
            return Objects.requireNonNull(acceptance, "source acceptance transaction result must not be null");
        } catch (InboxPersistenceConflictException exception) {
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private SourceAcceptance acceptWithinTransaction(NormalizedSourceEvent event) {
        lockTransport(event);
        TransportRecord transport = findLockedTransport(event);
        if (!transport.fingerprint().equals(event.fingerprint())) {
            recordConflict(event, transport.fingerprint(), SourceEventConflictScope.TRANSPORT_EVENT_ID);
            return contractFailure(SourceEventConflictScope.TRANSPORT_EVENT_ID);
        }

        boolean newCanonical = createCanonical(event);
        CanonicalRecord canonical = findLockedCanonical(event);
        if (!canonical.fingerprint().equals(event.fingerprint())) {
            recordConflict(event, canonical.fingerprint(), SourceEventConflictScope.CANONICAL_MESSAGE_ID);
            return contractFailure(SourceEventConflictScope.CANONICAL_MESSAGE_ID);
        }
        if (!newCanonical) {
            return duplicate(findLockedAdmission(event));
        }

        SessionId sessionId = createAndLockSession(event);
        long sessionSequence = allocateNextSessionSequence(sessionId);
        InboxMessageId inboxMessageId = identityGenerator.nextInboxMessageId();
        AnalysisRunId runId = identityGenerator.nextRunId();
        String deliveryId = identityGenerator.nextDeliveryId();
        insertInbox(event, inboxMessageId, sessionId, sessionSequence, runId);
        insertReceipt(event, deliveryId, inboxMessageId, runId);
        return accepted(new SourceAdmission(inboxMessageId, sessionId, sessionSequence, runId));
    }

    private void lockTransport(NormalizedSourceEvent event) {
        jdbcClient.sql("""
                INSERT INTO source_transport_event (
                    source_type, transport_event_id, source_message_id, source_key,
                    participant_source_type, participant_key, source_text, question_text,
                    payload_fingerprint, received_at, created_at
                ) VALUES (
                    :sourceType, :transportEventId, :sourceMessageId, :sourceKey,
                    :participantSourceType, :participantKey, :sourceText, :questionText,
                    :fingerprint, :receivedAt, CURRENT_TIMESTAMP
                )
                ON CONFLICT (source_type, transport_event_id) DO NOTHING
                """)
                .param("sourceType", event.sourceType())
                .param("transportEventId", event.transportEventId().value())
                .param("sourceMessageId", event.sourceMessageId().value())
                .param("sourceKey", event.sessionSource().sourceKey())
                .param("participantSourceType", event.participant().sourceType())
                .param("participantKey", event.participant().participantKey())
                .param("sourceText", event.sourceText())
                .param("questionText", event.questionText())
                .param("fingerprint", event.fingerprint().bytes())
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .update();
    }

    private TransportRecord findLockedTransport(NormalizedSourceEvent event) {
        Optional<TransportRecord> transport = jdbcClient.sql("""
                SELECT payload_fingerprint
                FROM source_transport_event
                WHERE source_type = :sourceType
                  AND transport_event_id = :transportEventId
                FOR UPDATE
                """)
                .param("sourceType", event.sourceType())
                .param("transportEventId", event.transportEventId().value())
                .query(this::mapTransportRecord)
                .optional();
        return transport.orElseThrow(InboxPersistenceConflictException::new);
    }

    private boolean createCanonical(NormalizedSourceEvent event) {
        int insertedRows = jdbcClient.sql("""
                INSERT INTO canonical_source_message (
                    source_type, source_message_id, source_key,
                    participant_source_type, participant_key, source_text, question_text,
                    payload_fingerprint, received_at, created_at
                ) VALUES (
                    :sourceType, :sourceMessageId, :sourceKey,
                    :participantSourceType, :participantKey, :sourceText, :questionText,
                    :fingerprint, :receivedAt, CURRENT_TIMESTAMP
                )
                ON CONFLICT (source_type, source_message_id) DO NOTHING
                """)
                .param("sourceType", event.sourceType())
                .param("sourceMessageId", event.sourceMessageId().value())
                .param("sourceKey", event.sessionSource().sourceKey())
                .param("participantSourceType", event.participant().sourceType())
                .param("participantKey", event.participant().participantKey())
                .param("sourceText", event.sourceText())
                .param("questionText", event.questionText())
                .param("fingerprint", event.fingerprint().bytes())
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .update();
        return insertedRows == 1;
    }

    private CanonicalRecord findLockedCanonical(NormalizedSourceEvent event) {
        Optional<CanonicalRecord> canonical = jdbcClient.sql("""
                SELECT payload_fingerprint
                FROM canonical_source_message
                WHERE source_type = :sourceType
                  AND source_message_id = :sourceMessageId
                FOR UPDATE
                """)
                .param("sourceType", event.sourceType())
                .param("sourceMessageId", event.sourceMessageId().value())
                .query(this::mapCanonicalRecord)
                .optional();
        return canonical.orElseThrow(InboxPersistenceConflictException::new);
    }

    private SourceAdmission findLockedAdmission(NormalizedSourceEvent event) {
        Optional<SourceAdmission> admission = jdbcClient.sql("""
                SELECT inbox.inbox_message_id, inbox.session_id, inbox.session_sequence, inbox.analysis_run_id
                FROM canonical_source_message canonical
                JOIN session_inbox inbox
                  ON inbox.source_type = canonical.source_type
                 AND inbox.source_message_id = canonical.source_message_id
                JOIN delivery_outbox receipt
                  ON receipt.inbox_message_id = inbox.inbox_message_id
                 AND receipt.delivery_kind = 'RECEIPT'
                WHERE canonical.source_type = :sourceType
                  AND canonical.source_message_id = :sourceMessageId
                FOR UPDATE OF inbox, receipt
                """)
                .param("sourceType", event.sourceType())
                .param("sourceMessageId", event.sourceMessageId().value())
                .query(this::mapSourceAdmission)
                .optional();
        return admission.orElseThrow(InboxPersistenceConflictException::new);
    }

    private SessionId createAndLockSession(NormalizedSourceEvent event) {
        SessionId proposedSessionId = identityGenerator.nextSessionId();
        jdbcClient.sql("""
                INSERT INTO agent_session (
                    session_id, source_type, source_key, next_inbox_sequence, next_turn_sequence, created_at
                ) VALUES (
                    :sessionId, :sourceType, :sourceKey, 0, 0, CURRENT_TIMESTAMP
                )
                ON CONFLICT (source_type, source_key) DO NOTHING
                """)
                .param("sessionId", proposedSessionId.value())
                .param("sourceType", event.sessionSource().sourceType())
                .param("sourceKey", event.sessionSource().sourceKey())
                .update();
        Optional<String> sessionId = jdbcClient.sql("""
                SELECT session_id
                FROM agent_session
                WHERE source_type = :sourceType
                  AND source_key = :sourceKey
                FOR UPDATE
                """)
                .param("sourceType", event.sessionSource().sourceType())
                .param("sourceKey", event.sessionSource().sourceKey())
                .query(String.class)
                .optional();
        return new SessionId(sessionId.orElseThrow(InboxPersistenceConflictException::new));
    }

    private long allocateNextSessionSequence(SessionId sessionId) {
        Optional<Long> allocatedSequence = jdbcClient.sql("""
                UPDATE agent_session
                SET next_inbox_sequence = next_inbox_sequence + 1
                WHERE session_id = :sessionId
                RETURNING next_inbox_sequence - 1
                """)
                .param("sessionId", sessionId.value())
                .query(Long.class)
                .optional();
        return allocatedSequence.orElseThrow(InboxPersistenceConflictException::new);
    }

    private void insertInbox(
            NormalizedSourceEvent event,
            InboxMessageId inboxMessageId,
            SessionId sessionId,
            long sessionSequence,
            AnalysisRunId runId) {
        int insertedRows = jdbcClient.sql("""
                INSERT INTO session_inbox (
                    inbox_message_id, source_type, source_message_id, session_id, session_sequence, analysis_run_id,
                    source_text, question_text, participant_source_type, participant_key, defer_reason,
                    status, attempt_count, available_at, claimed_at, last_error_code, last_error_description,
                    created_at, updated_at
                ) VALUES (
                    :inboxMessageId, :sourceType, :sourceMessageId, :sessionId, :sessionSequence, :runId,
                    :sourceText, :questionText, :participantSourceType, :participantKey, NULL,
                    'PENDING', 0, CURRENT_TIMESTAMP, NULL, NULL, NULL,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .param("sourceType", event.sourceType())
                .param("sourceMessageId", event.sourceMessageId().value())
                .param("sessionId", sessionId.value())
                .param("sessionSequence", sessionSequence)
                .param("runId", runId.value())
                .param("sourceText", event.sourceText())
                .param("questionText", event.questionText())
                .param("participantSourceType", event.participant().sourceType())
                .param("participantKey", event.participant().participantKey())
                .update();
        requireSingleAffectedRow(insertedRows);
    }

    private void insertReceipt(
            NormalizedSourceEvent event,
            String deliveryId,
            InboxMessageId inboxMessageId,
            AnalysisRunId runId) {
        int insertedRows = jdbcClient.sql("""
                INSERT INTO delivery_outbox (
                    delivery_id, inbox_message_id, analysis_run_id, delivery_kind, response_kind, outcome,
                    source_type, source_key, participant_source_type, participant_key, response_text,
                    status, attempt_count, next_attempt_at, last_failure_category, last_failure_description,
                    provider_message_id, created_at, updated_at
                ) VALUES (
                    :deliveryId, :inboxMessageId, :runId, 'RECEIPT', NULL, NULL,
                    :sourceType, :sourceKey, :participantSourceType, :participantKey, '已接收',
                    'PENDING', 0, CURRENT_TIMESTAMP, NULL, NULL,
                    NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)
                .param("deliveryId", deliveryId)
                .param("inboxMessageId", inboxMessageId.value())
                .param("runId", runId.value())
                .param("sourceType", event.sessionSource().sourceType())
                .param("sourceKey", event.sessionSource().sourceKey())
                .param("participantSourceType", event.participant().sourceType())
                .param("participantKey", event.participant().participantKey())
                .update();
        requireSingleAffectedRow(insertedRows);
    }

    private void recordConflict(
            NormalizedSourceEvent event,
            SourcePayloadFingerprintV1 authoritativeFingerprint,
            SourceEventConflictScope scope) {
        jdbcClient.sql("""
                INSERT INTO source_event_conflict (
                    conflict_id, conflict_scope, source_type, transport_event_id, source_message_id,
                    authoritative_payload_fingerprint, incoming_payload_fingerprint,
                    incoming_source_key, incoming_participant_source_type, incoming_participant_key,
                    incoming_source_text, incoming_question_text, received_at, failure_category, created_at
                ) VALUES (
                    :conflictId, :scope, :sourceType, :transportEventId, :sourceMessageId,
                    :authoritativeFingerprint, :incomingFingerprint,
                    :sourceKey, :participantSourceType, :participantKey,
                    :sourceText, :questionText, :receivedAt, :category, CURRENT_TIMESTAMP
                )
                ON CONFLICT (
                    conflict_scope, source_type, transport_event_id, source_message_id,
                    authoritative_payload_fingerprint, incoming_payload_fingerprint
                ) DO NOTHING
                """)
                .param("conflictId", identityGenerator.nextConflictId())
                .param("scope", scope.name())
                .param("sourceType", event.sourceType())
                .param("transportEventId", event.transportEventId().value())
                .param("sourceMessageId", event.sourceMessageId().value())
                .param("authoritativeFingerprint", authoritativeFingerprint.bytes())
                .param("incomingFingerprint", event.fingerprint().bytes())
                .param("sourceKey", event.sessionSource().sourceKey())
                .param("participantSourceType", event.participant().sourceType())
                .param("participantKey", event.participant().participantKey())
                .param("sourceText", event.sourceText())
                .param("questionText", event.questionText())
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .param("category", CONFLICT_CATEGORY)
                .update();
    }

    private TransactionTemplate dedicatedReadCommittedTemplate(TransactionTemplate configuredTemplate) {
        int configuredIsolation = configuredTemplate.getIsolationLevel();
        if (configuredIsolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                || configuredIsolation == TransactionDefinition.ISOLATION_SERIALIZABLE) {
            throw new IllegalArgumentException("source acceptance requires READ COMMITTED isolation");
        }
        PlatformTransactionManager transactionManager = Objects.requireNonNull(
                configuredTemplate.getTransactionManager(), "transaction manager must not be null");
        TransactionTemplate dedicatedTemplate = new TransactionTemplate(transactionManager);
        dedicatedTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        dedicatedTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return dedicatedTemplate;
    }

    private TransportRecord mapTransportRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TransportRecord(new SourcePayloadFingerprintV1(resultSet.getBytes("payload_fingerprint")));
    }

    private CanonicalRecord mapCanonicalRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CanonicalRecord(new SourcePayloadFingerprintV1(resultSet.getBytes("payload_fingerprint")));
    }

    private SourceAdmission mapSourceAdmission(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SourceAdmission(
                new InboxMessageId(resultSet.getString("inbox_message_id")),
                new SessionId(resultSet.getString("session_id")),
                resultSet.getLong("session_sequence"),
                new AnalysisRunId(resultSet.getString("analysis_run_id")));
    }

    private SourceAcceptance accepted(SourceAdmission admission) {
        return new SourceAcceptance(SourceAcceptanceStatus.ACCEPTED, Optional.of(admission), Optional.empty());
    }

    private SourceAcceptance duplicate(SourceAdmission admission) {
        return new SourceAcceptance(SourceAcceptanceStatus.DUPLICATE, Optional.of(admission), Optional.empty());
    }

    private SourceAcceptance contractFailure(SourceEventConflictScope scope) {
        return new SourceAcceptance(SourceAcceptanceStatus.CONTRACT_FAILED, Optional.empty(), Optional.of(scope));
    }

    private void requireSingleAffectedRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new InboxPersistenceConflictException();
        }
    }

    private record TransportRecord(SourcePayloadFingerprintV1 fingerprint) {
    }

    private record CanonicalRecord(SourcePayloadFingerprintV1 fingerprint) {
    }
}
