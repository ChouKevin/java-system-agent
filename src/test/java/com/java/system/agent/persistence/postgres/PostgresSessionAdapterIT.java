package com.java.system.agent.persistence.postgres;

import com.java.system.agent.persistence.jdbc.JdbcPersistenceException;
import com.java.system.agent.persistence.jdbc.PostgresSessionAdapter;
import com.java.system.agent.persistence.jdbc.SessionTurnConflictException;
import com.java.system.agent.runtime.domain.conversation.ConversationTurn;
import com.java.system.agent.runtime.domain.conversation.ConversationTurnType;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.conversation.SessionHistory;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgresSessionAdapter 的真實 PostgreSQL append-only session turn 行為驗證
 */
class PostgresSessionAdapterIT extends PostgresIntegrationTestSupport {

    private static final ParticipantRef PARTICIPANT = new ParticipantRef("slack", "U123456");

    private DataSource dataSource;
    private JdbcClient jdbcClient;
    private PostgresSessionAdapter sessions;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        dataSource = newDataSource();
        jdbcClient = JdbcClient.create(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        sessions = new PostgresSessionAdapter(jdbcClient, transactionTemplate);
    }

    @Test
    void readsAnExistingSessionWithNoTurnsAsAnEmptyHistory() {
        insertSession("session-empty");

        assertThat(sessions.read(new SessionId("session-empty"))).isEqualTo(SessionHistory.empty());
    }

    @Test
    void appendsDistinctRunsInAllocatedSequenceAndReadsTheCompleteOrderedHistory() {
        SessionId sessionId = new SessionId("session-ordered");
        ConversationTurn first = turn("run-1", "first question", "first response", ConversationTurnType.ANSWER);
        ConversationTurn second = turn("run-2", "second question", "second response", ConversationTurnType.CLARIFICATION);
        insertSession(sessionId.value());

        sessions.append(sessionId, first);
        sessions.append(sessionId, second);

        assertThat(sessions.read(sessionId).turns()).containsExactly(first, second);
        assertThat(nextTurnSequence(sessionId)).isEqualTo(2L);
    }

    @Test
    void retainsEachParticipantExactlyAcrossOneSessionHistory() {
        SessionId sessionId = new SessionId("session-participants");
        ConversationTurn alice = new ConversationTurn(
                new AnalysisRunId("run-alice"), new ParticipantRef("slack", "U-ALICE"),
                "alice question", "alice response", ConversationTurnType.ANSWER);
        ConversationTurn bob = new ConversationTurn(
                new AnalysisRunId("run-bob"), new ParticipantRef("slack", "U-BOB"),
                "bob question", "bob response", ConversationTurnType.CLARIFICATION);
        insertSession(sessionId.value());

        sessions.append(sessionId, alice);
        sessions.append(sessionId, bob);

        assertThat(sessions.read(sessionId).turns()).containsExactly(alice, bob);
    }

    @Test
    void rejectsADuplicateRunWhenOnlyItsParticipantChanges() {
        SessionId sessionId = new SessionId("session-participant-conflict");
        ConversationTurn accepted = new ConversationTurn(
                new AnalysisRunId("run-1"), new ParticipantRef("slack", "U-ALICE"),
                "question", "response", ConversationTurnType.ANSWER);
        ConversationTurn changedParticipant = new ConversationTurn(
                new AnalysisRunId("run-1"), new ParticipantRef("slack", "U-BOB"),
                "question", "response", ConversationTurnType.ANSWER);
        insertSession(sessionId.value());
        sessions.append(sessionId, accepted);

        assertThatThrownBy(() -> sessions.append(sessionId, changedParticipant))
                .isInstanceOf(SessionTurnConflictException.class);
        assertThat(sessions.read(sessionId).turns()).containsExactly(accepted);
    }

    @Test
    void treatsAnIdenticalAppendAsANoOpWithoutConsumingAnotherSequence() {
        SessionId sessionId = new SessionId("session-duplicate");
        ConversationTurn accepted = turn("run-1", "question", "response", ConversationTurnType.ANSWER);
        insertSession(sessionId.value());

        sessions.append(sessionId, accepted);
        sessions.append(sessionId, accepted);

        assertThat(sessions.read(sessionId).turns()).containsExactly(accepted);
        assertThat(nextTurnSequence(sessionId)).isEqualTo(1L);
    }

    @Test
    void serializesOverlappingDistinctRunAppendsWithinOneSession() throws Exception {
        SessionId sessionId = new SessionId("session-concurrent-distinct");
        ConversationTurn first = turn("run-1", "first question", "first response", ConversationTurnType.ANSWER);
        ConversationTurn second = turn("run-2", "second question", "second response", ConversationTurnType.ANSWER);
        insertSession(sessionId.value());

        assertThat(appendWhileAllocationIsLocked(sessionId,
                () -> sessions.append(sessionId, first),
                () -> sessions.append(sessionId, second))).isEmpty();

        assertThat(sessions.read(sessionId).turns()).containsExactlyInAnyOrder(first, second);
        assertThat(turnSequences(sessionId)).containsExactly(0L, 1L);
        assertThat(nextTurnSequence(sessionId)).isEqualTo(2L);
    }

    /**
     * 配置鎖使兩個相同 append 都完成交易內 reread 並阻塞於 allocation update
     * 釋放後其中一個 insert 成功，另一個 insert 撞上 unique constraint 並 rollback 後 reread
     */
    @Test
    void resolvesIdenticalConcurrentAppendsThroughTheUniqueRaceAsOneTurn() throws Exception {
        SessionId sessionId = new SessionId("session-concurrent-same-run");
        ConversationTurn accepted = turn("run-1", "question", "response", ConversationTurnType.ANSWER);
        insertSession(sessionId.value());

        assertThat(appendWhileAllocationIsLocked(sessionId,
                () -> sessions.append(sessionId, accepted),
                () -> sessions.append(sessionId, accepted))).isEmpty();

        assertThat(sessions.read(sessionId).turns()).containsExactly(accepted);
        assertThat(turnSequences(sessionId)).containsExactly(0L);
        assertThat(nextTurnSequence(sessionId)).isEqualTo(1L);
    }

    @Test
    void rejectsOneOfOverlappingDifferentPayloadsForTheSameRunWithoutConsumingAnotherSequence() throws Exception {
        SessionId sessionId = new SessionId("session-concurrent-conflict");
        ConversationTurn first = turn("run-1", "question", "first response", ConversationTurnType.ANSWER);
        ConversationTurn second = turn("run-1", "question", "second response", ConversationTurnType.ANSWER);
        insertSession(sessionId.value());

        List<Throwable> failures = appendWhileAllocationIsLocked(sessionId,
                () -> sessions.append(sessionId, first),
                () -> sessions.append(sessionId, second));

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(SessionTurnConflictException.class);
        assertThat(sessions.read(sessionId).turns())
                .hasSize(1)
                .containsAnyOf(first, second);
        assertThat(turnSequences(sessionId)).containsExactly(0L);
        assertThat(nextTurnSequence(sessionId)).isEqualTo(1L);
    }

    @ParameterizedTest
    @MethodSource("conflictingTurns")
    void rejectsAnAppendForTheSameSessionAndRunWhenAnyAcceptedContentDiffers(ConversationTurn conflicting) {
        SessionId sessionId = new SessionId("session-conflict");
        ConversationTurn accepted = turn("run-1", "question", "response", ConversationTurnType.ANSWER);
        insertSession(sessionId.value());
        sessions.append(sessionId, accepted);

        assertThatThrownBy(() -> sessions.append(sessionId, conflicting))
                .isInstanceOf(SessionTurnConflictException.class)
                .hasNoCause()
                .hasMessage("durable session turn persistence conflict");
        assertThat(sessions.read(sessionId).turns()).containsExactly(accepted);
        assertThat(nextTurnSequence(sessionId)).isEqualTo(1L);
    }

    @Test
    void rejectsAnAppendForAnUnknownSessionWithoutCreatingASessionMapping() {
        SessionId sessionId = new SessionId("missing-session");

        assertThatThrownBy(() -> sessions.append(sessionId,
                turn("run-1", "question", "response", ConversationTurnType.ANSWER)))
                .isInstanceOf(SessionTurnConflictException.class);
        assertThat(countSessions(sessionId)).isZero();
    }

    @Test
    void translatesProviderFailuresToTheSafePersistenceException() {
        newFlyway().clean();

        assertThatThrownBy(() -> sessions.read(new SessionId("session-unavailable")))
                .isInstanceOf(JdbcPersistenceException.class)
                .hasNoCause()
                .hasMessage("durable persistence operation failed")
                .satisfies(throwable -> assertThat(throwable.getMessage())
                        .doesNotContain("session_turn", "PostgreSQL", "jdbc:", "localhost", "http"));
    }

    private static Stream<Arguments> conflictingTurns() {
        return Stream.of(
                Arguments.of(turn("run-1", "changed question", "response", ConversationTurnType.ANSWER)),
                Arguments.of(turn("run-1", "question", "changed response", ConversationTurnType.ANSWER)),
                Arguments.of(turn("run-1", "question", "response", ConversationTurnType.CLARIFICATION)));
    }

    private void insertSession(String sessionId) {
        jdbcClient.sql("""
                INSERT INTO agent_session (
                    session_id, source_type, source_key, next_inbox_sequence, next_turn_sequence, created_at
                ) VALUES (
                    :sessionId, 'slack', :sourceKey, 0, 0, CURRENT_TIMESTAMP
                )
                """)
                .param("sessionId", sessionId)
                .param("sourceKey", "source-" + sessionId)
                .update();
    }

    private long nextTurnSequence(SessionId sessionId) {
        return jdbcClient.sql("""
                SELECT next_turn_sequence
                FROM agent_session
                WHERE session_id = :sessionId
                """)
                .param("sessionId", sessionId.value())
                .query(Long.class)
                .single();
    }

    private long countSessions(SessionId sessionId) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                FROM agent_session
                WHERE session_id = :sessionId
                """)
                .param("sessionId", sessionId.value())
                .query(Long.class)
                .single();
    }

    private List<Long> turnSequences(SessionId sessionId) {
        return jdbcClient.sql("""
                SELECT turn_sequence
                FROM session_turn
                WHERE session_id = :sessionId
                ORDER BY turn_sequence
                """)
                .param("sessionId", sessionId.value())
                .query(Long.class)
                .list();
    }

    private List<Throwable> appendWhileAllocationIsLocked(SessionId sessionId, Runnable... operations) throws Exception {
        try (Connection allocationLock = dataSource.getConnection()) {
            allocationLock.setAutoCommit(false);
            lockSessionAllocation(allocationLock, sessionId);
            return appendAfterBothCallsWaitForAllocationLock(sessionId, allocationLock, operations);
        }
    }

    private void lockSessionAllocation(Connection allocationLock, SessionId sessionId) throws Exception {
        try (PreparedStatement statement = allocationLock.prepareStatement("""
                SELECT session_id
                FROM agent_session
                WHERE session_id = ?
                FOR UPDATE
                """)) {
            statement.setString(1, sessionId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AssertionError("session allocation lock target was not found");
                }
            }
        }
    }

    private List<Throwable> appendAfterBothCallsWaitForAllocationLock(
            SessionId sessionId,
            Connection allocationLock,
            Runnable... operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        List<Future<Void>> futures = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        boolean completed = false;
        boolean allocationReleased = false;
        try {
            for (Runnable operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent append start barrier timed out");
                    }
                    operation.run();
                    return null;
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent append workers were not ready");
            }
            start.countDown();
            awaitBlockedAllocationUpdates(sessionId, operations.length);
            allocationLock.commit();
            allocationReleased = true;
            for (Future<Void> future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException exception) {
                    failures.add(Objects.requireNonNull(exception.getCause()));
                }
            }
            completed = true;
            return failures;
        } finally {
            if (!allocationReleased) {
                allocationLock.rollback();
            }
            if (completed) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
            }
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent append executor did not terminate");
            }
        }
    }

    private void awaitBlockedAllocationUpdates(SessionId sessionId, int expectedWaiters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long blockedUpdates = jdbcClient.sql("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND state = 'active'
                      AND wait_event_type = 'Lock'
                      AND query LIKE 'UPDATE agent_session%'
                      AND query LIKE '%next_turn_sequence%'
                    """)
                    .query(Long.class)
                    .single();
            if (blockedUpdates >= expectedWaiters) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("append calls did not both block on the session allocation lock for " + sessionId.value());
    }

    private static ConversationTurn turn(
            String runId,
            String userMessage,
            String assistantMessage,
            ConversationTurnType type) {
        return new ConversationTurn(new AnalysisRunId(runId), PARTICIPANT, userMessage, assistantMessage, type);
    }
}
