package com.java.system.agent.persistence.jdbc;

import com.java.system.agent.persistence.document.AgentEventDocumentCodec;
import com.java.system.agent.persistence.document.AgentStateDocumentCodec;
import com.java.system.agent.persistence.document.VersionedJsonDocument;
import com.java.system.agent.answering.domain.run.AgentBootstrap;
import com.java.system.agent.answering.domain.run.AgentEvent;
import com.java.system.agent.answering.domain.run.AgentRunState;
import com.java.system.agent.answering.domain.run.AgentTransition;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import com.java.system.agent.answering.domain.run.RunRequestIdentity;
import com.java.system.agent.answering.port.out.AgentTransitionConflictException;
import com.java.system.agent.answering.port.out.AgentTransitionPort;
import com.java.system.agent.answering.port.out.TerminalAcceptanceCancelledException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PostgreSQL agent snapshot 與 append-only event trace 的原子 JDBC adapter
 */
public final class PostgresAgentTransitionAdapter implements AgentTransitionPort {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final AgentStateDocumentCodec stateCodec;
    private final AgentEventDocumentCodec eventCodec;

    public PostgresAgentTransitionAdapter(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            AgentStateDocumentCodec stateCodec,
            AgentEventDocumentCodec eventCodec) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbc client must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transaction template must not be null");
        this.stateCodec = Objects.requireNonNull(stateCodec, "agent state codec must not be null");
        this.eventCodec = Objects.requireNonNull(eventCodec, "agent event codec must not be null");
    }

    @Override
    public AgentRunState bootstrap(AgentBootstrap bootstrap) {
        Objects.requireNonNull(bootstrap, "agent bootstrap must not be null");
        validateBootstrap(bootstrap);
        try {
            EncodedTransition runStarted = encode(bootstrap.runStarted());
            EncodedTransition attemptStarted = encode(bootstrap.attemptStarted());
            EncodedTransition contextIssued = encode(bootstrap.contextIssued());
            EncodedState finalState = encodeState(bootstrap.finalTransition().candidateState());
            return executeInTransaction(() -> bootstrapWithinTransaction(
                    bootstrap.finalTransition().candidateState(),
                    finalState,
                    runStarted,
                    attemptStarted,
                    contextIssued));
        } catch (AgentTransitionConflictException exception) {
            throw exception;
        } catch (JdbcPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }

    @Override
    public AgentRunState commit(AgentTransition transition) {
        Objects.requireNonNull(transition, "agent transition must not be null");
        validateOrdinaryTransition(transition);
        try {
            EncodedTransition encoded = encode(transition);
            return executeInTransaction(() -> commitWithinTransaction(transition, encoded));
        } catch (AgentTransitionConflictException exception) {
            throw exception;
        } catch (JdbcPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }

    @Override
    public AgentRunState commitTerminalAcceptance(AgentTransition transition) {
        Objects.requireNonNull(transition, "terminal acceptance transition must not be null");
        validateTerminalAcceptance(transition);
        try {
            EncodedTransition encoded = encode(transition);
            return executeInTransaction(() -> commitTerminalAcceptanceWithinTransaction(transition, encoded));
        } catch (AgentTransitionConflictException | TerminalAcceptanceCancelledException exception) {
            throw exception;
        } catch (JdbcPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }

    @Override
    public Optional<AgentRunState> findByRunId(AnalysisRunId runId) {
        Objects.requireNonNull(runId, "analysis run ID must not be null");
        try {
            Optional<StoredState> stored = jdbcClient.sql("""
                    SELECT run_id, session_id, question_text, status, state_revision, state_schema_version,
                           current_state::text AS current_state
                    FROM agent_run
                    WHERE run_id = :runId
                    """)
                    .param("runId", runId.value())
                    .query(this::mapStoredState)
                    .optional();
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            AgentRunState decoded = stateCodec.decode(
                    stored.orElseThrow().stateSchemaVersion(),
                    stored.orElseThrow().currentState());
            validateStoredState(runId, stored.orElseThrow(), decoded);
            return Optional.of(decoded);
        } catch (JdbcPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private AgentRunState bootstrapWithinTransaction(
            AgentRunState finalState,
            EncodedState encodedState,
            EncodedTransition runStarted,
            EncodedTransition attemptStarted,
            EncodedTransition contextIssued) {
        int insertedRunRows = jdbcClient.sql("""
                INSERT INTO agent_run (
                    run_id, session_id, question_text, status, state_revision, state_schema_version,
                    current_state, cancellation_requested, created_at, updated_at
                ) VALUES (
                    :runId, :sessionId, :questionText, :status, :stateRevision, :stateSchemaVersion,
                    CAST(:currentState AS jsonb), FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) ON CONFLICT (run_id) DO NOTHING
                """)
                .param("runId", finalState.runId().value())
                .param("sessionId", finalState.requestIdentity().sessionIdValue())
                .param("questionText", finalState.requestIdentity().questionText())
                .param("status", finalState.status().name())
                .param("stateRevision", finalState.stateRevision())
                .param("stateSchemaVersion", encodedState.schemaVersion())
                .param("currentState", encodedState.payload())
                .update();
        requireSingleAffectedRow(insertedRunRows);
        insertEvent(runStarted);
        insertEvent(attemptStarted);
        insertEvent(contextIssued);
        return finalState;
    }

    private AgentRunState commitWithinTransaction(AgentTransition transition, EncodedTransition encoded) {
        updateSnapshot(transition, encoded.state());
        insertEvent(encoded);
        return transition.candidateState();
    }

    private AgentRunState commitTerminalAcceptanceWithinTransaction(AgentTransition transition, EncodedTransition encoded) {
        Optional<LockedRun> locked = jdbcClient.sql("""
                SELECT state_revision, cancellation_requested
                FROM agent_run
                WHERE run_id = :runId
                FOR UPDATE
                """)
                .param("runId", transition.event().runId().value())
                .query(this::mapLockedRun)
                .optional();
        if (locked.isEmpty()) {
            throw new AgentTransitionConflictException("agent run transition ownership was not acquired");
        }
        LockedRun lockedRun = locked.orElseThrow();
        if (lockedRun.cancellationRequested()) {
            throw new TerminalAcceptanceCancelledException("durable cancellation won terminal arbitration");
        }
        if (lockedRun.stateRevision() != transition.event().expectedStateRevision()) {
            throw new AgentTransitionConflictException("agent run transition ownership was not acquired");
        }
        return commitWithinTransaction(transition, encoded);
    }

    private void updateSnapshot(AgentTransition transition, EncodedState encodedState) {
        int updatedRows = jdbcClient.sql("""
                UPDATE agent_run
                SET status = :status,
                    state_revision = :stateRevision,
                    state_schema_version = :stateSchemaVersion,
                    current_state = CAST(:currentState AS jsonb),
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = :runId
                  AND state_revision = :expectedStateRevision
                  AND session_id = :sessionId
                  AND question_text = :questionText
                """)
                .param("status", transition.candidateState().status().name())
                .param("stateRevision", transition.candidateState().stateRevision())
                .param("stateSchemaVersion", encodedState.schemaVersion())
                .param("currentState", encodedState.payload())
                .param("runId", transition.event().runId().value())
                .param("expectedStateRevision", transition.event().expectedStateRevision())
                .param("sessionId", transition.candidateState().requestIdentity().sessionIdValue())
                .param("questionText", transition.candidateState().requestIdentity().questionText())
                .update();
        requireSingleAffectedRow(updatedRows);
    }

    private void insertEvent(EncodedTransition transition) {
        int insertedRows = jdbcClient.sql("""
                INSERT INTO agent_run_event (
                    run_id, state_revision, event_type, event_schema_version, payload, created_at
                ) VALUES (
                    :runId, :stateRevision, :eventType, :eventSchemaVersion, CAST(:payload AS jsonb), CURRENT_TIMESTAMP
                ) ON CONFLICT (run_id, state_revision) DO NOTHING
                """)
                .param("runId", transition.event().runId().value())
                .param("stateRevision", transition.candidateState().stateRevision())
                .param("eventType", transition.eventType())
                .param("eventSchemaVersion", transition.eventDocument().schemaVersion())
                .param("payload", transition.eventDocument().payload())
                .update();
        requireSingleAffectedRow(insertedRows);
    }

    private EncodedTransition encode(AgentTransition transition) {
        return new EncodedTransition(
                transition.event(),
                transition.candidateState(),
                eventCodec.eventType(transition.event()),
                encodeEvent(transition.event()),
                encodeState(transition.candidateState()));
    }

    private EncodedState encodeState(AgentRunState state) {
        VersionedJsonDocument document = stateCodec.encode(state);
        return new EncodedState(document.schemaVersion(), document.payload().toString());
    }

    private EncodedDocument encodeEvent(AgentEvent event) {
        VersionedJsonDocument document = eventCodec.encode(event);
        return new EncodedDocument(document.schemaVersion(), document.payload().toString());
    }

    private void validateBootstrap(AgentBootstrap bootstrap) {
        validateBootstrapTransition(bootstrap.runStarted(), AgentEvent.RunStarted.class, 0);
        validateBootstrapTransition(bootstrap.attemptStarted(), AgentEvent.AttemptStarted.class, 1);
        validateBootstrapTransition(bootstrap.contextIssued(), AgentEvent.ContextIssued.class, 2);
        AgentRunState runStartedState = bootstrap.runStarted().candidateState();
        AgentRunState attemptStartedState = bootstrap.attemptStarted().candidateState();
        AgentRunState contextIssuedState = bootstrap.contextIssued().candidateState();
        if (!runStartedState.runId().equals(attemptStartedState.runId())
                || !runStartedState.runId().equals(contextIssuedState.runId())) {
            throw new IllegalArgumentException("bootstrap candidate states must belong to one run");
        }
        RunRequestIdentity runStartedIdentity = runStartedState.requestIdentity();
        if (!runStartedIdentity.equals(attemptStartedState.requestIdentity())
                || !runStartedIdentity.equals(contextIssuedState.requestIdentity())) {
            throw new IllegalArgumentException("bootstrap candidate states must share one request identity");
        }
        if (!bootstrap.runStarted().event().attemptId().equals(runStartedState.currentAttempt().attemptId())
                || !bootstrap.attemptStarted().event().attemptId().equals(attemptStartedState.currentAttempt().attemptId())
                || !bootstrap.contextIssued().event().attemptId().equals(contextIssuedState.currentAttempt().attemptId())) {
            throw new IllegalArgumentException("bootstrap event attempt IDs must match their candidate state");
        }
        if (!bootstrap.runStarted().event().attemptId().equals(bootstrap.attemptStarted().event().attemptId())
                || !bootstrap.runStarted().event().attemptId().equals(bootstrap.contextIssued().event().attemptId())) {
            throw new IllegalArgumentException("initial bootstrap events must belong to one attempt");
        }
        AgentEvent.AttemptStarted attemptStarted = (AgentEvent.AttemptStarted) bootstrap.attemptStarted().event();
        if (!attemptStarted.newAttempt().attemptId().equals(attemptStartedState.currentAttempt().attemptId())) {
            throw new IllegalArgumentException("bootstrap attempt start must publish its candidate attempt");
        }
    }

    private void validateBootstrapTransition(
            AgentTransition transition,
            Class<? extends AgentEvent> expectedType,
            long expectedRevision) {
        validateTransitionEnvelope(transition);
        if (!expectedType.isInstance(transition.event())
                || transition.event().expectedStateRevision() != expectedRevision
                || transition.candidateState().stateRevision() != expectedRevision + 1) {
            throw new IllegalArgumentException("bootstrap transitions must use ordered event types and revisions");
        }
    }

    private void validateOrdinaryTransition(AgentTransition transition) {
        validateTransitionEnvelope(transition);
        if (transition.event() instanceof AgentEvent.RunStarted
                || transition.event() instanceof AgentEvent.AttemptStarted
                && transition.event().expectedStateRevision() == 1
                || transition.event() instanceof AgentEvent.ContextIssued
                && transition.event().expectedStateRevision() == 2) {
            throw new IllegalArgumentException("bootstrap-only events cannot be committed ordinarily");
        }
    }

    private void validateTerminalAcceptance(AgentTransition transition) {
        validateTransitionEnvelope(transition);
        if (!(transition.event() instanceof AgentEvent.AnswerAccepted)
                && !(transition.event() instanceof AgentEvent.ClarificationAccepted)) {
            throw new IllegalArgumentException("terminal acceptance requires an accepted terminal event");
        }
    }

    private void validateTransitionEnvelope(AgentTransition transition) {
        if (!transition.event().runId().equals(transition.candidateState().runId())) {
            throw new IllegalArgumentException("agent event and candidate state must belong to one run");
        }
        if (transition.candidateState().stateRevision() != transition.event().expectedStateRevision() + 1) {
            throw new IllegalArgumentException("candidate state revision must follow the event expected revision");
        }
        if (transition.event() instanceof AgentEvent.AttemptStarted attemptStarted
                && !attemptStarted.newAttempt().equals(transition.candidateState().currentAttempt())) {
            throw new IllegalArgumentException("attempt start must publish its candidate attempt");
        }
        if (!(transition.event() instanceof AgentEvent.AttemptStarted)
                && !transition.event().attemptId().equals(transition.candidateState().currentAttempt().attemptId())) {
            throw new IllegalArgumentException("agent event attempt ID must match the candidate state");
        }
    }

    private void validateStoredState(AnalysisRunId requestedRunId, StoredState stored, AgentRunState decoded) {
        if (!requestedRunId.equals(decoded.runId())
                || !stored.runId().equals(decoded.runId().value())
                || stored.stateRevision() != decoded.stateRevision()
                || !stored.status().equals(decoded.status().name())
                || !stored.sessionId().equals(decoded.requestIdentity().sessionIdValue())
                || !stored.questionText().equals(decoded.requestIdentity().questionText())) {
            throw new JdbcPersistenceException();
        }
    }

    private StoredState mapStoredState(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredState(
                resultSet.getString("run_id"),
                resultSet.getString("session_id"),
                resultSet.getString("question_text"),
                resultSet.getString("status"),
                resultSet.getLong("state_revision"),
                resultSet.getInt("state_schema_version"),
                resultSet.getString("current_state"));
    }

    private LockedRun mapLockedRun(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LockedRun(resultSet.getLong("state_revision"), resultSet.getBoolean("cancellation_requested"));
    }

    private <T> T executeInTransaction(Supplier<T> operation) {
        try {
            T result = transactionTemplate.execute(transactionStatus -> operation.get());
            return Objects.requireNonNull(result, "transaction result must not be null");
        } catch (AgentTransitionConflictException | TerminalAcceptanceCancelledException exception) {
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw new JdbcPersistenceException();
        }
    }

    private void requireSingleAffectedRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new AgentTransitionConflictException("agent run transition ownership was not acquired");
        }
    }

    /**
     * 已編碼的 agent snapshot 文件
     */
    private record EncodedState(int schemaVersion, String payload) {
    }

    /**
     * 已編碼的 agent event 文件
     */
    private record EncodedDocument(int schemaVersion, String payload) {
    }

    /**
     * event、candidate snapshot 與兩份持久化文件的固定組合
     */
    private record EncodedTransition(
            AgentEvent event,
            AgentRunState candidateState,
            String eventType,
            EncodedDocument eventDocument,
            EncodedState state) {
    }

    /**
     * agent_run relational snapshot 欄位
     */
    private record StoredState(
            String runId,
            String sessionId,
            String questionText,
            String status,
            long stateRevision,
            int stateSchemaVersion,
            String currentState) {
    }

    /**
     * terminal acceptance 仲裁期間鎖住的 relational 欄位
     */
    private record LockedRun(long stateRevision, boolean cancellationRequested) {
    }
}
