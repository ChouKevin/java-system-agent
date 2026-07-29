package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.answering.domain.conversation.ConversationTurn;
import com.java.system.agent.answering.domain.conversation.ConversationTurnType;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionHistory;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.port.out.SessionPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PostgreSQL session history 的 immutable turn JDBC adapter
 */
public final class PostgresSessionAdapter implements SessionPort {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public PostgresSessionAdapter(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbc client must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transaction template must not be null");
    }

    @Override
    public SessionHistory read(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "session ID must not be null");
        try {
            List<ConversationTurn> turns = jdbcClient.sql("""
                    SELECT run_id, participant_source_type, participant_key, user_message, assistant_message, turn_type
                    FROM session_turn
                    WHERE session_id = :sessionId
                    ORDER BY turn_sequence
                    """)
                    .param("sessionId", sessionId.value())
                    .query(this::mapTurn)
                    .list();
            return new SessionHistory(turns);
        } catch (JdbcPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }

    @Override
    public void append(SessionId sessionId, ConversationTurn turn) {
        Objects.requireNonNull(sessionId, "session ID must not be null");
        Objects.requireNonNull(turn, "accepted conversation turn must not be null");
        try {
            Optional<ConversationTurn> existing = findExistingTurn(sessionId, turn.runId());
            if (existing.isPresent()) {
                requireIdentical(existing.orElseThrow(), turn);
                return;
            }
            executeInTransaction(() -> appendNewTurn(sessionId, turn));
        } catch (SessionTurnConflictException exception) {
            throw exception;
        } catch (JdbcPersistenceException exception) {
            resolveUniqueRace(sessionId, turn, exception);
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private boolean appendNewTurn(SessionId sessionId, ConversationTurn turn) {
        Optional<ConversationTurn> existing = findExistingTurn(sessionId, turn.runId());
        if (existing.isPresent()) {
            requireIdentical(existing.orElseThrow(), turn);
            return true;
        }
        Optional<Long> allocatedSequence = jdbcClient.sql("""
                UPDATE agent_session
                SET next_turn_sequence = next_turn_sequence + 1
                WHERE session_id = :sessionId
                RETURNING next_turn_sequence - 1
                """)
                .param("sessionId", sessionId.value())
                .query(Long.class)
                .optional();
        if (allocatedSequence.isEmpty()) {
            throw new SessionTurnConflictException();
        }
        int insertedRows = jdbcClient.sql("""
                INSERT INTO session_turn (
                    session_id, run_id, turn_sequence, participant_source_type, participant_key, user_message, assistant_message, turn_type, created_at
                ) VALUES (
                    :sessionId, :runId, :turnSequence, :participantSourceType, :participantKey, :userMessage, :assistantMessage, :turnType, CURRENT_TIMESTAMP
                )
                """)
                .param("sessionId", sessionId.value())
                .param("runId", turn.runId().value())
                .param("turnSequence", allocatedSequence.orElseThrow())
                .param("participantSourceType", turn.participant().sourceType())
                .param("participantKey", turn.participant().participantKey())
                .param("userMessage", turn.userMessage())
                .param("assistantMessage", turn.assistantMessage())
                .param("turnType", turn.type().name())
                .update();
        if (insertedRows != 1) {
            throw new SessionTurnConflictException();
        }
        return true;
    }

    private void resolveUniqueRace(
            SessionId sessionId,
            ConversationTurn turn,
            JdbcPersistenceException originalFailure) {
        try {
            Optional<ConversationTurn> existing = findExistingTurn(sessionId, turn.runId());
            if (existing.isPresent()) {
                requireIdentical(existing.orElseThrow(), turn);
                return;
            }
            throw originalFailure;
        } catch (SessionTurnConflictException exception) {
            throw exception;
        } catch (JdbcPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private Optional<ConversationTurn> findExistingTurn(SessionId sessionId, AnalysisRunId runId) {
        return jdbcClient.sql("""
                SELECT run_id, participant_source_type, participant_key, user_message, assistant_message, turn_type
                FROM session_turn
                WHERE session_id = :sessionId
                  AND run_id = :runId
                """)
                .param("sessionId", sessionId.value())
                .param("runId", runId.value())
                .query(this::mapTurn)
                .optional();
    }

    private ConversationTurn mapTurn(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ConversationTurn(
                new AnalysisRunId(resultSet.getString("run_id")),
                new ParticipantRef(resultSet.getString("participant_source_type"), resultSet.getString("participant_key")),
                resultSet.getString("user_message"),
                resultSet.getString("assistant_message"),
                ConversationTurnType.valueOf(resultSet.getString("turn_type")));
    }

    private void requireIdentical(ConversationTurn existing, ConversationTurn proposed) {
        if (!existing.equals(proposed)) {
            throw new SessionTurnConflictException();
        }
    }

    private <T> T executeInTransaction(Supplier<T> operation) {
        try {
            T result = transactionTemplate.execute(transactionStatus -> operation.get());
            return Objects.requireNonNull(result, "transaction result must not be null");
        } catch (SessionTurnConflictException exception) {
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw new JdbcPersistenceException();
        }
    }
}
