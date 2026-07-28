package com.java.system.agent.persistence.postgres;

import com.java.system.agent.inbox.domain.InboxClaim;
import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.NormalizedSourceEvent;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceAdmission;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.inbox.domain.TransportEventId;
import com.java.system.agent.inbox.domain.delivery.DeliveryClaim;
import com.java.system.agent.inbox.domain.delivery.DeliveryFailure;
import com.java.system.agent.inbox.domain.delivery.DeliveryKind;
import com.java.system.agent.inbox.port.out.InboxIdentityGenerator;
import com.java.system.agent.persistence.jdbc.PostgresDeliveryOutboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSourceAcceptanceAdapter;
import com.java.system.agent.runtime.domain.conversation.ParticipantRef;
import com.java.system.agent.runtime.domain.conversation.SessionId;
import com.java.system.agent.runtime.domain.run.AnalysisRunId;
import com.java.system.agent.runtime.domain.run.RunOutcome;
import com.java.system.agent.runtime.domain.run.RunResponseKind;
import com.java.system.agent.runtime.domain.scope.RevisionVector;
import com.java.system.agent.runtime.port.in.AnswerQuestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL delivery claim、receipt promotion 與 recovery 的整合測試
 */
class PostgresDeliveryOutboxAdapterIT extends PostgresIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");
    private PostgresSessionInboxAdapter inbox;
    private PostgresSourceAcceptanceAdapter acceptance;
    private PostgresDeliveryOutboxAdapter delivery;
    private JdbcClient jdbcClient;
    private DataSource dataSource;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        dataSource = newDataSource();
        jdbcClient = JdbcClient.create(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Identities identities = new Identities();
        inbox = new PostgresSessionInboxAdapter(jdbcClient, transactionTemplate, identities);
        acceptance = new PostgresSourceAcceptanceAdapter(jdbcClient, transactionTemplate, identities);
        delivery = new PostgresDeliveryOutboxAdapter(jdbcClient, transactionTemplate);
    }

    @Test
    void receiptDeliveredWhileFinalCreationWaitsOnTheReceiptLock() throws Exception {
        assertReceiptTerminalFirstInterleaving(ReceiptTerminal.DELIVERED);
    }

    @Test
    void schedulesExactRetryAndRecoversInterruptedDeliveryWithoutChangingAttempts() {
        admit("event-1", "message-1");
        DeliveryClaim first = delivery.claimNext(NOW).orElseThrow();
        Instant retryAt = NOW.plusSeconds(45);

        delivery.recordRetry(first, new DeliveryFailure("NETWORK_AMBIGUOUS", "connection reset"), retryAt, NOW);

        assertThat(delivery.claimNext(NOW.plusSeconds(44))).isEmpty();
        DeliveryClaim retry = delivery.claimNext(retryAt).orElseThrow();
        assertThat(retry.message().attemptCount()).isEqualTo(2);
        assertThat(delivery.recoverInterrupted(NOW.plusSeconds(46))).isEqualTo(1);
        DeliveryClaim recovered = delivery.claimNext(NOW.plusSeconds(46)).orElseThrow();
        assertThat(recovered.message().attemptCount()).isEqualTo(3);
    }

    @Test
    void blocksTheWaitingFinalResponseWhenSlackRejectsTheReceiptPermanently() {
        SourceAdmission admission = admit("event-poison", "message-poison");
        InboxClaim inboxClaim = inbox.claimNext(NOW).orElseThrow();
        inbox.completeWithFinal(inboxClaim, result(inboxClaim.message().runId()), NOW);
        DeliveryClaim receipt = delivery.claimNext(NOW).orElseThrow();

        delivery.recordBlocked(receipt, new DeliveryFailure("SLACK_MISSING_SCOPE", "Slack rejected delivery"), NOW);

        assertThat(status(admission, DeliveryKind.RECEIPT)).isEqualTo("BLOCKED");
        assertThat(failure(admission, DeliveryKind.RECEIPT)).isEqualTo("SLACK_MISSING_SCOPE");
        assertThat(status(admission, DeliveryKind.FINAL_RESPONSE)).isEqualTo("BLOCKED");
        assertThat(failure(admission, DeliveryKind.FINAL_RESPONSE)).isEqualTo("PREDECESSOR_BLOCKED");
    }

    @Test
    void receiptBlockedWhileFinalCreationWaitsOnTheReceiptLock() throws Exception {
        assertReceiptTerminalFirstInterleaving(ReceiptTerminal.BLOCKED);
    }

    @Test
    void finalCreationWhileReceiptDeliveryWaitsOnTheReceiptLock() throws Exception {
        assertFinalCreationFirstInterleaving(ReceiptTerminal.DELIVERED);
    }

    @Test
    void finalCreationWhileReceiptBlockWaitsOnTheReceiptLock() throws Exception {
        assertFinalCreationFirstInterleaving(ReceiptTerminal.BLOCKED);
    }

    private SourceAdmission admit(String eventId, String messageId) {
        String sourceText = "<@bot> question " + messageId;
        return acceptance.accept(new NormalizedSourceEvent(
                "slack", new TransportEventId(eventId), new SourceMessageId(messageId),
                new SessionSourceRef("slack", "channel-1:thread-1"), new ParticipantRef("slack", "U123456"),
                sourceText, "question " + messageId, SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace-1", "channel-1", messageId, "thread-1", "U123456", sourceText), NOW))
                .admission()
                .orElseThrow();
    }

    private AnswerQuestionResult result(AnalysisRunId runId) {
        return new AnswerQuestionResult(
                runId, RunOutcome.INCONCLUSIVE, "資訊不足", Optional.empty(), RunResponseKind.RUNTIME_NOTICE,
                Optional.empty(), RevisionVector.empty());
    }

    private String status(SourceAdmission admission, DeliveryKind kind) {
        return jdbcClient.sql("""
                SELECT status FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId AND delivery_kind = :kind
                """)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .param("kind", kind.name())
                .query(String.class)
                .single();
    }

    private String providerMessageId(SourceAdmission admission, DeliveryKind kind) {
        return jdbcClient.sql("""
                SELECT provider_message_id FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId AND delivery_kind = :kind
                """)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .param("kind", kind.name())
                .query(String.class)
                .single();
    }

    private String failure(SourceAdmission admission, DeliveryKind kind) {
        return jdbcClient.sql("""
                SELECT last_failure_category FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId AND delivery_kind = :kind
                """)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .param("kind", kind.name())
                .query(String.class)
                .single();
    }

    private long finalCount(SourceAdmission admission) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId AND delivery_kind = 'FINAL_RESPONSE'
                """)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .query(Long.class)
                .single();
    }

    private void assertReceiptTerminalFirstInterleaving(ReceiptTerminal terminal) throws Exception {
        SourceAdmission admission = admit("event-receipt-first-" + terminal, "message-receipt-first-" + terminal);
        InboxClaim inboxClaim = inbox.claimNext(NOW).orElseThrow();
        CountDownLatch receiptLocked = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        SqlObservedDataSource observedDataSource = new SqlObservedDataSource(
                dataSource, PostgresDeliveryOutboxAdapterIT::isReceiptForUpdate, new CountDownLatch(1), Optional.empty());
        PostgresSessionInboxAdapter observedInbox = new PostgresSessionInboxAdapter(
                JdbcClient.create(observedDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(observedDataSource)),
                new Identities());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> receiptTransaction = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                lockAndSetReceipt(admission, terminal);
                receiptLocked.countDown();
                await(releaseReceipt, "receipt transaction release");
            }));
            await(receiptLocked, "receipt row lock");
            Future<?> finalCreation = executor.submit(() -> observedInbox.completeWithFinal(
                    inboxClaim, result(inboxClaim.message().runId()), NOW));
            await(observedDataSource.sqlReached(), "final receipt FOR UPDATE");
            assertThat(finalCreation.isDone()).isFalse();

            releaseReceipt.countDown();
            receiptTransaction.get(10, TimeUnit.SECONDS);
            finalCreation.get(10, TimeUnit.SECONDS);

            assertThat(status(admission, DeliveryKind.RECEIPT)).isEqualTo(terminal.name());
            assertTerminalFinal(admission, terminal);
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("receipt-first interleaving executor did not terminate");
            }
        }
    }

    private void assertFinalCreationFirstInterleaving(ReceiptTerminal terminal) throws Exception {
        SourceAdmission admission = admit("event-final-first-" + terminal, "message-final-first-" + terminal);
        InboxClaim inboxClaim = inbox.claimNext(NOW).orElseThrow();
        DeliveryClaim receiptClaim = delivery.claimNext(NOW).orElseThrow();
        CountDownLatch finalCommitReached = new CountDownLatch(1);
        CountDownLatch releaseFinalCommit = new CountDownLatch(1);
        SqlObservedDataSource commitGatedDataSource = new SqlObservedDataSource(
                dataSource, ignored -> false, new CountDownLatch(0), Optional.of(new CommitGate(finalCommitReached, releaseFinalCommit)));
        PostgresSessionInboxAdapter commitGatedInbox = new PostgresSessionInboxAdapter(
                JdbcClient.create(commitGatedDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(commitGatedDataSource)),
                new Identities());
        SqlObservedDataSource observedDataSource = new SqlObservedDataSource(
                dataSource, PostgresDeliveryOutboxAdapterIT::isReceiptTerminalUpdate, new CountDownLatch(1), Optional.empty());
        PostgresDeliveryOutboxAdapter observedDelivery = new PostgresDeliveryOutboxAdapter(
                JdbcClient.create(observedDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(observedDataSource)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> finalCreation = executor.submit(() -> commitGatedInbox.completeWithFinal(
                    inboxClaim, result(inboxClaim.message().runId()), NOW));
            await(finalCommitReached, "final creation commit gate");
            Future<?> receiptTerminal = executor.submit(() -> recordReceiptTerminal(
                    observedDelivery, receiptClaim, terminal));
            await(observedDataSource.sqlReached(), "receipt terminal update");
            assertThat(receiptTerminal.isDone()).isFalse();

            releaseFinalCommit.countDown();
            finalCreation.get(10, TimeUnit.SECONDS);
            receiptTerminal.get(10, TimeUnit.SECONDS);

            assertThat(status(admission, DeliveryKind.RECEIPT)).isEqualTo(terminal.name());
            assertTerminalFinal(admission, terminal);
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("final-first interleaving executor did not terminate");
            }
        }
    }

    private void lockAndSetReceipt(SourceAdmission admission, ReceiptTerminal terminal) {
        jdbcClient.sql("""
                SELECT status
                FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId
                  AND delivery_kind = 'RECEIPT'
                FOR UPDATE
                """)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .query(String.class)
                .single();
        jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = :status,
                    provider_message_id = :providerMessageId,
                    last_failure_category = :failureCategory,
                    last_failure_description = :failureDescription,
                    updated_at = :updatedAt
                WHERE inbox_message_id = :inboxMessageId
                  AND delivery_kind = 'RECEIPT'
                """)
                .param("status", terminal.name())
                .param("providerMessageId", terminal == ReceiptTerminal.DELIVERED ? "provider-receipt-1" : null)
                .param("failureCategory", terminal == ReceiptTerminal.BLOCKED ? "SLACK_DENIED" : null)
                .param("failureDescription", terminal == ReceiptTerminal.BLOCKED ? "channel unavailable" : null)
                .param("updatedAt", Timestamp.from(NOW))
                .param("inboxMessageId", admission.inboxMessageId().value())
                .update();
    }

    private void recordReceiptTerminal(
            PostgresDeliveryOutboxAdapter deliveryAdapter,
            DeliveryClaim receiptClaim,
            ReceiptTerminal terminal) {
        if (terminal == ReceiptTerminal.DELIVERED) {
            deliveryAdapter.recordDelivered(receiptClaim, "provider-receipt-1", NOW.plusSeconds(1));
            return;
        }
        deliveryAdapter.recordBlocked(
                receiptClaim, new DeliveryFailure("SLACK_DENIED", "channel unavailable"), NOW.plusSeconds(1));
    }

    private void assertTerminalFinal(SourceAdmission admission, ReceiptTerminal terminal) {
        if (terminal == ReceiptTerminal.DELIVERED) {
            assertThat(providerMessageId(admission, DeliveryKind.RECEIPT)).isEqualTo("provider-receipt-1");
            assertThat(status(admission, DeliveryKind.FINAL_RESPONSE)).isEqualTo("PENDING");
        } else {
            assertThat(status(admission, DeliveryKind.FINAL_RESPONSE)).isEqualTo("BLOCKED");
            assertThat(failure(admission, DeliveryKind.FINAL_RESPONSE)).isEqualTo("PREDECESSOR_BLOCKED");
        }
        assertThat(finalCount(admission)).isEqualTo(1L);
    }

    private static boolean isReceiptForUpdate(String sql) {
        return sql.contains("FROM delivery_outbox")
                && sql.contains("delivery_kind = 'RECEIPT'")
                && sql.contains("FOR UPDATE");
    }

    private static boolean isReceiptTerminalUpdate(String sql) {
        return sql.contains("UPDATE delivery_outbox")
                && sql.contains("WHERE delivery_id");
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(operation + " did not reach its synchronization point");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(operation + " was interrupted", exception);
        }
    }

    private enum ReceiptTerminal {
        DELIVERED,
        BLOCKED
    }

    private record CommitGate(CountDownLatch reached, CountDownLatch release) {
    }

    private static final class SqlObservedDataSource implements DataSource {

        private final DataSource delegate;
        private final Predicate<String> observedSql;
        private final CountDownLatch sqlReached;
        private final Optional<CommitGate> commitGate;

        private SqlObservedDataSource(
                DataSource delegate,
                Predicate<String> observedSql,
                CountDownLatch sqlReached,
                Optional<CommitGate> commitGate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate data source must not be null");
            this.observedSql = Objects.requireNonNull(observedSql, "observed SQL predicate must not be null");
            this.sqlReached = Objects.requireNonNull(sqlReached, "SQL reached latch must not be null");
            this.commitGate = Objects.requireNonNull(commitGate, "commit gate must not be null");
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return connection(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> interfaceType) throws SQLException {
            return delegate.unwrap(interfaceType);
        }

        @Override
        public boolean isWrapperFor(Class<?> interfaceType) throws SQLException {
            return delegate.isWrapperFor(interfaceType);
        }

        private CountDownLatch sqlReached() {
            return sqlReached;
        }

        private Connection connection(Connection connection) {
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement")
                        && arguments.length > 0
                        && arguments[0] instanceof String sql) {
                    PreparedStatement statement = (PreparedStatement) invoke(connection, method, arguments);
                    return statement(statement, sql);
                }
                if (method.getName().equals("commit") && commitGate.isPresent()) {
                    CommitGate gate = commitGate.orElseThrow();
                    gate.reached().countDown();
                    await(gate.release(), "final creation commit release");
                }
                return invoke(connection, method, arguments);
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement statement(PreparedStatement statement, String sql) {
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (observedSql.test(sql)
                        && (method.getName().equals("execute") || method.getName().equals("executeQuery")
                        || method.getName().equals("executeUpdate"))) {
                    sqlReached.countDown();
                }
                return invoke(statement, method, arguments);
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private static final class Identities implements InboxIdentityGenerator {

        private final String prefix = UUID.randomUUID().toString();
        private int sequence;

        @Override
        public InboxMessageId nextInboxMessageId() {
            return new InboxMessageId(prefix + "-inbox-" + nextSequence());
        }

        @Override
        public SessionId nextSessionId() {
            return new SessionId(prefix + "-session-" + nextSequence());
        }

        @Override
        public AnalysisRunId nextRunId() {
            return new AnalysisRunId(prefix + "-run-" + nextSequence());
        }

        @Override
        public String nextDeliveryId() {
            return prefix + "-delivery-" + nextSequence();
        }

        @Override
        public String nextConflictId() {
            return prefix + "-conflict-" + nextSequence();
        }

        private int nextSequence() {
            sequence = Math.incrementExact(sequence);
            return sequence;
        }
    }
}
