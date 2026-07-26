package com.java.system.agent.persistence.postgres;

import com.java.system.agent.inbox.domain.InboxEnqueueRequest;
import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.InboxMessageStatus;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.port.out.InboxIdentityGenerator;
import com.java.system.agent.persistence.jdbc.InboxPersistenceConflictException;
import com.java.system.agent.persistence.jdbc.JdbcPersistenceException;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgresSessionInboxAdapter 的真實 PostgreSQL durable inbox 行為驗證
 */
class PostgresSessionInboxAdapterIT extends PostgresIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");
    private DataSource dataSource;
    private JdbcClient jdbcClient;
    private PostgresSessionInboxAdapter inbox;
    private DeterministicInboxIdentityGenerator identities;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        dataSource = newDataSource();
        jdbcClient = JdbcClient.create(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        identities = new DeterministicInboxIdentityGenerator();
        inbox = new PostgresSessionInboxAdapter(jdbcClient, transactionTemplate, identities);
    }

    @Test
    void enqueuesByTwoPartSourceAndReturnsTheExistingRowForAnExactDuplicate() {
        SessionSourceRef source = new SessionSourceRef("slack", "channel-1:thread-1");
        InboxMessage first = inbox.enqueue(request(source, "message-1", "first question"));
        InboxMessage second = inbox.enqueue(request(source, "message-2", "second question"));
        InboxMessage duplicate = inbox.enqueue(request(source, "message-1", "first question"));

        assertThat(first.inboxMessageId()).isEqualTo(new InboxMessageId("inbox-1"));
        assertThat(first.source()).isEqualTo(source);
        assertThat(first.sourceMessageId()).isEqualTo(new SourceMessageId("message-1"));
        assertThat(first.sessionId()).isEqualTo(new SessionId("session-1"));
        assertThat(first.sessionSequence()).isZero();
        assertThat(first.runId()).isEqualTo(new AnalysisRunId("run-1"));
        assertThat(first.exactQuestion()).isEqualTo("first question");
        assertThat(first.status()).isEqualTo(InboxMessageStatus.PENDING);
        assertThat(first.attemptCount()).isZero();
        assertThat(first.claimedAt()).isEmpty();
        assertThat(first.lastFailure()).isEmpty();
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(second.sessionSequence()).isEqualTo(1);
        assertThat(duplicate).isEqualTo(first);
        assertThat(identities.generatedInboxMessageCount()).isEqualTo(2);
        assertThat(identities.generatedSessionCount()).isEqualTo(1);
        assertThat(identities.generatedRunCount()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                SELECT source_type, source_key
                FROM agent_session
                WHERE session_id = :sessionId
                """)
                .param("sessionId", first.sessionId().value())
                .query((resultSet, rowNumber) -> new StoredSource(
                        resultSet.getString("source_type"), resultSet.getString("source_key")))
                .single()).isEqualTo(new StoredSource("slack", "channel-1:thread-1"));

        assertThatThrownBy(() -> inbox.enqueue(request(source, "message-1", "changed question")))
                .isInstanceOf(InboxPersistenceConflictException.class);
        assertThatThrownBy(() -> inbox.enqueue(request(
                new SessionSourceRef("slack", "channel-2:thread-1"), "message-1", "first question")))
                .isInstanceOf(InboxPersistenceConflictException.class);
    }

    @Test
    void claimsOnlySessionHeadsAndReleasesTheNextHeadAfterATerminalFailure() {
        SessionSourceRef firstSource = new SessionSourceRef("slack", "channel-1:thread-1");
        SessionSourceRef secondSource = new SessionSourceRef("slack", "channel-2:thread-1");
        InboxMessage firstHead = inbox.enqueue(request(firstSource, "message-1", "first head"));
        InboxMessage firstFollower = inbox.enqueue(request(firstSource, "message-2", "first follower"));
        InboxMessage secondHead = inbox.enqueue(request(secondSource, "message-3", "second head"));

        InboxMessage claimedFirstHead = inbox.claimNext(NOW).orElseThrow();
        InboxMessage claimedSecondHead = newIndependentInbox().claimNext(NOW).orElseThrow();

        assertThat(claimedFirstHead.inboxMessageId()).isEqualTo(firstHead.inboxMessageId());
        assertThat(claimedFirstHead.attemptCount()).isEqualTo(1);
        assertThat(claimedSecondHead.inboxMessageId()).isEqualTo(secondHead.inboxMessageId());

        InboxFailure retryFailure = new InboxFailure("TEMPORARY", "retry later");
        Instant retryAt = NOW.plusSeconds(30);
        inbox.retry(claimedFirstHead, retryFailure, retryAt);

        assertThat(inbox.claimNext(NOW)).isEmpty();
        InboxMessage retriedHead = inbox.claimNext(retryAt).orElseThrow();
        assertThat(retriedHead.inboxMessageId()).isEqualTo(firstHead.inboxMessageId());
        assertThat(retriedHead.attemptCount()).isEqualTo(2);
        assertThat(retriedHead.lastFailure()).contains(retryFailure);

        inbox.fail(retriedHead, new InboxFailure("FINAL", "cannot continue"), retryAt);

        InboxMessage releasedFollower = inbox.claimNext(retryAt).orElseThrow();
        assertThat(releasedFollower.inboxMessageId()).isEqualTo(firstFollower.inboxMessageId());
    }

    @Test
    void concurrentEnqueuesForOneSourceAllocateOneSessionDistinctRunsAndContiguousSequences() throws Exception {
        SessionSourceRef source = new SessionSourceRef("slack", "channel-concurrent:thread-1");
        List<InboxMessage> messages = invokeConcurrently(
                () -> inbox.enqueue(request(source, "message-concurrent-1", "first question")),
                () -> newIndependentInbox().enqueue(request(source, "message-concurrent-2", "second question")));

        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(InboxMessage::sessionId).containsOnly(messages.getFirst().sessionId());
        assertThat(messages).extracting(InboxMessage::runId).doesNotHaveDuplicates();
        assertThat(messages).extracting(InboxMessage::sessionSequence).containsExactlyInAnyOrder(0L, 1L);
        assertThat(jdbcClient.sql("""
                SELECT COUNT(*)
                FROM agent_session
                WHERE source_type = :sourceType
                  AND source_key = :sourceKey
                """)
                .param("sourceType", source.sourceType())
                .param("sourceKey", source.sourceKey())
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void overlappingClaimsSkipALockedSessionHeadKeepItsFollowerBlockedAndClaimAnotherSessionHead() throws Exception {
        SessionSourceRef firstSource = new SessionSourceRef("slack", "channel-claim-1:thread-1");
        SessionSourceRef secondSource = new SessionSourceRef("slack", "channel-claim-2:thread-1");
        InboxMessage firstHead = inbox.enqueue(request(firstSource, "message-claim-1", "first head"));
        InboxMessage follower = inbox.enqueue(request(firstSource, "message-claim-2", "follower"));
        InboxMessage otherHead = inbox.enqueue(request(secondSource, "message-claim-3", "other head"));

        try (Connection firstClaimTransaction = dataSource.getConnection()) {
            firstClaimTransaction.setAutoCommit(false);
            lockInboxMessage(firstClaimTransaction, firstHead);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Optional<InboxMessage>> competingClaim = executor.submit(
                        () -> newIndependentInbox().claimNext(NOW));
                InboxMessage claimedOtherHead = competingClaim.get(10, TimeUnit.SECONDS).orElseThrow();

                assertThat(claimedOtherHead.inboxMessageId()).isEqualTo(otherHead.inboxMessageId());
                assertThat(claimedOtherHead.inboxMessageId()).isNotEqualTo(follower.inboxMessageId());
            } finally {
                firstClaimTransaction.rollback();
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("overlapping claim executor did not terminate");
                }
            }
        }

        InboxMessage claimedFirstHead = inbox.claimNext(NOW).orElseThrow();
        assertThat(claimedFirstHead.inboxMessageId()).isEqualTo(firstHead.inboxMessageId());
        assertThat(newIndependentInbox().claimNext(NOW)).isEmpty();

        inbox.fail(claimedFirstHead, new InboxFailure("FINAL", "cannot continue"), NOW);

        assertThat(newIndependentInbox().claimNext(NOW).orElseThrow().inboxMessageId())
                .isEqualTo(follower.inboxMessageId());
    }

    @Test
    void rejectsStaleTransitionsAndRecoversInterruptedClaimsWithoutDroppingFailureMetadata() {
        SessionSourceRef source = new SessionSourceRef("slack", "channel-1:thread-1");
        inbox.enqueue(request(source, "message-1", "question"));
        InboxMessage firstClaim = inbox.claimNext(NOW).orElseThrow();
        InboxMessage staleClaim = processingCopy(firstClaim, 2);

        assertThatThrownBy(() -> inbox.complete(staleClaim, NOW)).isInstanceOf(InboxPersistenceConflictException.class);
        assertThatThrownBy(() -> inbox.retry(staleClaim, new InboxFailure("STALE", "stale retry"), NOW))
                .isInstanceOf(InboxPersistenceConflictException.class);
        assertThatThrownBy(() -> inbox.fail(staleClaim, new InboxFailure("STALE", "stale failure"), NOW))
                .isInstanceOf(InboxPersistenceConflictException.class);

        inbox.complete(firstClaim, NOW);
        InboxMessage recoveryMessage = inbox.enqueue(request(source, "message-2", "recover this message"));
        InboxFailure retryFailure = new InboxFailure("TEMPORARY", "recover this claim");
        InboxMessage recoveryFirstClaim = inbox.claimNext(NOW).orElseThrow();
        inbox.retry(recoveryFirstClaim, retryFailure, NOW);
        InboxMessage interruptedClaim = inbox.claimNext(NOW).orElseThrow();

        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(5))).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                SELECT status, attempt_count, claimed_at, available_at, last_error_code, last_error_description
                FROM session_inbox
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", recoveryMessage.inboxMessageId().value())
                .query((resultSet, rowNumber) -> new StoredRecovery(
                        resultSet.getString("status"),
                        resultSet.getInt("attempt_count"),
                        Optional.ofNullable(resultSet.getTimestamp("claimed_at")),
                        resultSet.getTimestamp("available_at").toInstant(),
                        resultSet.getString("last_error_code"),
                        resultSet.getString("last_error_description")))
                .single()).isEqualTo(new StoredRecovery(
                "PENDING", interruptedClaim.attemptCount(), Optional.empty(), NOW.plusSeconds(5),
                retryFailure.code(), retryFailure.description()));

        InboxMessage recoveredClaim = inbox.claimNext(NOW.plusSeconds(5)).orElseThrow();
        inbox.complete(recoveredClaim, NOW.plusSeconds(6));

        assertThat(jdbcClient.sql("""
                SELECT status, claimed_at, last_error_code, last_error_description
                FROM session_inbox
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", recoveryMessage.inboxMessageId().value())
                .query((resultSet, rowNumber) -> new StoredTerminal(
                        resultSet.getString("status"),
                        Optional.ofNullable(resultSet.getTimestamp("claimed_at")),
                        Optional.ofNullable(resultSet.getString("last_error_code")),
                        Optional.ofNullable(resultSet.getString("last_error_description"))))
                .single()).isEqualTo(new StoredTerminal(
                "COMPLETED", Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void translatesPersistenceFailuresToASafePublicException() {
        newFlyway().clean();

        assertThatThrownBy(() -> inbox.claimNext(NOW))
                .isInstanceOf(JdbcPersistenceException.class)
                .hasNoCause()
                .satisfies(throwable -> {
                    assertThat(throwable.getMessage()).isEqualTo("durable persistence operation failed");
                    assertThat(throwable.getMessage())
                            .doesNotContain("session_inbox", "agent_session", "PostgreSQL", "jdbc:", "localhost", "http");
                });
    }

    private static InboxEnqueueRequest request(SessionSourceRef source, String sourceMessageId, String question) {
        return new InboxEnqueueRequest(source, new SourceMessageId(sourceMessageId), question);
    }

    private void lockInboxMessage(Connection transaction, InboxMessage message) throws Exception {
        try (PreparedStatement statement = transaction.prepareStatement("""
                SELECT inbox_message_id
                FROM session_inbox
                WHERE inbox_message_id = ?
                FOR UPDATE
                """)) {
            statement.setString(1, message.inboxMessageId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AssertionError("overlap lock target was not found");
                }
            }
        }
    }

    private PostgresSessionInboxAdapter newIndependentInbox() {
        DataSource dataSource = newDataSource();
        return new PostgresSessionInboxAdapter(
                JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                identities);
    }

    @SafeVarargs
    private final List<InboxMessage> invokeConcurrently(Callable<InboxMessage>... operations)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        try {
            List<Future<InboxMessage>> futures = new ArrayList<>();
            for (Callable<InboxMessage> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent enqueue start barrier timed out");
                    }
                    return operation.call();
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent enqueue workers were not ready");
            }
            start.countDown();
            List<InboxMessage> messages = new ArrayList<>();
            for (Future<InboxMessage> future : futures) {
                messages.add(future.get(10, TimeUnit.SECONDS));
            }
            return messages;
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent enqueue executor did not terminate");
            }
        }
    }

    private static InboxMessage processingCopy(InboxMessage message, int attemptCount) {
        return new InboxMessage(
                message.inboxMessageId(), message.source(), message.sourceMessageId(), message.sessionId(),
                message.sessionSequence(), message.runId(), message.exactQuestion(), InboxMessageStatus.PROCESSING,
                attemptCount, message.availableAt(), message.claimedAt(), message.lastFailure());
    }

    /**
     * 資料庫讀回的 session source 欄位
     */
    private record StoredSource(String sourceType, String sourceKey) {
    }

    /**
     * 資料庫讀回的 interrupted claim 復原欄位
     */
    private record StoredRecovery(
            String status,
            int attemptCount,
            Optional<Timestamp> claimedAt,
            Instant availableAt,
            String failureCode,
            String failureDescription) {
    }

    /**
     * 資料庫讀回的 terminal inbox 欄位
     */
    private record StoredTerminal(
            String status,
            Optional<Timestamp> claimedAt,
            Optional<String> failureCode,
            Optional<String> failureDescription) {
    }

    /**
     * 讓持久化行為測試可精確斷言 identity 配發次數的固定 generator
     */
    private static final class DeterministicInboxIdentityGenerator implements InboxIdentityGenerator {

        private int inboxMessageCount;
        private int sessionCount;
        private int runCount;

        @Override
        public synchronized InboxMessageId nextInboxMessageId() {
            inboxMessageCount++;
            return new InboxMessageId("inbox-" + inboxMessageCount);
        }

        @Override
        public synchronized SessionId nextSessionId() {
            sessionCount++;
            return new SessionId("session-" + sessionCount);
        }

        @Override
        public synchronized AnalysisRunId nextRunId() {
            runCount++;
            return new AnalysisRunId("run-" + runCount);
        }

        private int generatedInboxMessageCount() {
            return inboxMessageCount;
        }

        private int generatedSessionCount() {
            return sessionCount;
        }

        private int generatedRunCount() {
            return runCount;
        }
    }
}
