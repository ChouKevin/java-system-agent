package com.java.system.agent.persistence.postgres;

import com.java.system.agent.interaction.domain.InboxMessageId;
import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceAcceptance;
import com.java.system.agent.interaction.domain.SourceAcceptanceStatus;
import com.java.system.agent.interaction.domain.SourceEventConflictScope;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.interaction.domain.TransportEventId;
import com.java.system.agent.interaction.port.out.InboxIdentityGenerator;
import com.java.system.agent.persistence.jdbc.PostgresSourceAcceptanceAdapter;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import com.java.system.agent.answering.domain.conversation.SessionId;
import com.java.system.agent.answering.domain.run.AnalysisRunId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgresSourceAcceptanceAdapter 的真實 PostgreSQL 原子來源 admission 驗證
 */
class PostgresSourceAcceptanceAdapterIT extends PostgresIntegrationTestSupport {

    private static final Instant RECEIVED_AT = Instant.parse("2030-07-28T10:00:00Z");
    private DataSource dataSource;
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        dataSource = newDataSource();
        jdbcClient = JdbcClient.create(dataSource);
    }

    @Test
    void acceptsOneOfTwoConcurrentDuplicatesAndReturnsTheSameDurableAdmission() throws Exception {
        NormalizedSourceEvent event = event("envelope-1", "message-1", "thread-1", "first source text");
        PostgresSourceAcceptanceAdapter firstAdapter = newAdapter("first");
        PostgresSourceAcceptanceAdapter secondAdapter = newAdapter("second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SourceAcceptance> first = executor.submit(awaitAndAccept(firstAdapter, event, ready, start));
            Future<SourceAcceptance> second = executor.submit(awaitAndAccept(secondAdapter, event, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            SourceAcceptance firstAcceptance = first.get(20, TimeUnit.SECONDS);
            SourceAcceptance secondAcceptance = second.get(20, TimeUnit.SECONDS);

            assertThat(List.of(firstAcceptance.status(), secondAcceptance.status()))
                    .containsExactlyInAnyOrder(SourceAcceptanceStatus.ACCEPTED, SourceAcceptanceStatus.DUPLICATE);
            assertThat(firstAcceptance.admission()).hasValue(secondAcceptance.admission().orElseThrow());
        }

        assertThat(rowCount("source_transport_event")).isEqualTo(1);
        assertThat(rowCount("canonical_source_message")).isEqualTo(1);
        assertThat(rowCount("agent_session")).isEqualTo(1);
        assertThat(rowCount("session_inbox")).isEqualTo(1);
        assertThat(rowCount("delivery_outbox")).isEqualTo(1);
        assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void recordsTransportIdentityConflictWithoutChangingTheOriginalAdmission() {
        PostgresSourceAcceptanceAdapter adapter = newAdapter("transport");
        SourceAcceptance accepted = adapter.accept(event("envelope-1", "message-1", "thread-1", "original source text"));

        SourceAcceptance conflict = adapter.accept(event("envelope-1", "message-2", "thread-2", "changed source text"));
        SourceAcceptance repeatedConflict = adapter.accept(event("envelope-1", "message-2", "thread-2", "changed source text"));

        assertThat(accepted.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
        assertThat(conflict.status()).isEqualTo(SourceAcceptanceStatus.CONTRACT_FAILED);
        assertThat(conflict.conflictScope()).contains(SourceEventConflictScope.TRANSPORT_EVENT_ID);
        assertThat(conflict.admission()).isEmpty();
        assertThat(repeatedConflict).isEqualTo(conflict);
        assertThat(rowCount("source_transport_event")).isEqualTo(1);
        assertThat(rowCount("canonical_source_message")).isEqualTo(1);
        assertThat(rowCount("source_event_conflict")).isEqualTo(1);
        assertThat(rowCount("agent_session")).isEqualTo(1);
        assertThat(rowCount("session_inbox")).isEqualTo(1);
        assertThat(rowCount("delivery_outbox")).isEqualTo(1);
    }

    @Test
    void recordsCanonicalIdentityConflictWithoutChangingTheOriginalAdmission() {
        PostgresSourceAcceptanceAdapter adapter = newAdapter("canonical");
        SourceAcceptance accepted = adapter.accept(event("envelope-1", "message-1", "thread-1", "original source text"));

        SourceAcceptance conflict = adapter.accept(event("envelope-2", "message-1", "thread-1", "changed source text"));
        SourceAcceptance repeatedConflict = adapter.accept(event("envelope-2", "message-1", "thread-1", "changed source text"));

        assertThat(accepted.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
        assertThat(conflict.status()).isEqualTo(SourceAcceptanceStatus.CONTRACT_FAILED);
        assertThat(conflict.conflictScope()).contains(SourceEventConflictScope.CANONICAL_MESSAGE_ID);
        assertThat(conflict.admission()).isEmpty();
        assertThat(repeatedConflict).isEqualTo(conflict);
        assertThat(rowCount("source_transport_event")).isEqualTo(2);
        assertThat(rowCount("canonical_source_message")).isEqualTo(1);
        assertThat(rowCount("source_event_conflict")).isEqualTo(1);
        assertThat(rowCount("agent_session")).isEqualTo(1);
        assertThat(rowCount("session_inbox")).isEqualTo(1);
        assertThat(rowCount("delivery_outbox")).isEqualTo(1);
    }

    @Test
    void serializesConcurrentMessagesForOneNewSessionSource() throws Exception {
        PostgresSourceAcceptanceAdapter firstAdapter = newAdapter("first-session");
        PostgresSourceAcceptanceAdapter secondAdapter = newAdapter("second-session");
        NormalizedSourceEvent firstEvent = event("envelope-1", "message-1", "thread-1", "first source text");
        NormalizedSourceEvent secondEvent = event("envelope-2", "message-2", "thread-1", "second source text");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SourceAcceptance> first = executor.submit(awaitAndAccept(firstAdapter, firstEvent, ready, start));
            Future<SourceAcceptance> second = executor.submit(awaitAndAccept(secondAdapter, secondEvent, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            SourceAcceptance firstAcceptance = first.get(20, TimeUnit.SECONDS);
            SourceAcceptance secondAcceptance = second.get(20, TimeUnit.SECONDS);

            assertThat(firstAcceptance.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
            assertThat(secondAcceptance.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
            assertThat(firstAcceptance.admission()).hasValueSatisfying(firstAdmission ->
                    assertThat(secondAcceptance.admission()).hasValueSatisfying(secondAdmission -> {
                        assertThat(secondAdmission.sessionId()).isEqualTo(firstAdmission.sessionId());
                        assertThat(List.of(firstAdmission.sessionSequence(), secondAdmission.sessionSequence()))
                                .containsExactlyInAnyOrder(0L, 1L);
                        assertThat(secondAdmission.inboxMessageId()).isNotEqualTo(firstAdmission.inboxMessageId());
                        assertThat(secondAdmission.runId()).isNotEqualTo(firstAdmission.runId());
                    }));
        }

        assertThat(rowCount("agent_session")).isEqualTo(1);
        assertThat(rowCount("session_inbox")).isEqualTo(2);
        assertThat(rowCount("delivery_outbox")).isEqualTo(2);
    }

    @Test
    void rejectsRepeatableReadAndSerializableAcceptanceTemplates() {
        TransactionTemplate repeatableRead = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionTemplate serializable = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        assertThatThrownBy(() -> new PostgresSourceAcceptanceAdapter(jdbcClient, repeatableRead, new Identities("repeatable")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresSourceAcceptanceAdapter(jdbcClient, serializable, new Identities("serializable")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commitsAdmissionWhenTheAmbientSerializableTransactionRollsBack() {
        PostgresSourceAcceptanceAdapter adapter = newAdapter("independent");
        TransactionTemplate ambientTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ambientTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);

        SourceAcceptance acceptance = ambientTransaction.execute(transactionStatus -> {
            jdbcClient.sql("SELECT 1").query(Integer.class).single();
            SourceAcceptance accepted = adapter.accept(event("envelope-1", "message-1", "thread-1", "source text"));
            transactionStatus.setRollbackOnly();
            return accepted;
        });

        assertThat(acceptance).isNotNull();
        assertThat(acceptance.status()).isEqualTo(SourceAcceptanceStatus.ACCEPTED);
        assertThat(rowCount("source_transport_event")).isEqualTo(1);
        assertThat(rowCount("canonical_source_message")).isEqualTo(1);
        assertThat(rowCount("agent_session")).isEqualTo(1);
        assertThat(rowCount("session_inbox")).isEqualTo(1);
        assertThat(rowCount("delivery_outbox")).isEqualTo(1);
        assertThat(receiptCount()).isEqualTo(1);
    }

    private PostgresSourceAcceptanceAdapter newAdapter(String prefix) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new PostgresSourceAcceptanceAdapter(jdbcClient, transactionTemplate, new Identities(prefix));
    }

    private Callable<SourceAcceptance> awaitAndAccept(
            PostgresSourceAcceptanceAdapter adapter,
            NormalizedSourceEvent event,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent test start was not released");
            }
            return adapter.accept(event);
        };
    }

    private NormalizedSourceEvent event(
            String transportEventId,
            String sourceMessageId,
            String threadId,
            String sourceText) {
        return new NormalizedSourceEvent(
                "slack",
                new TransportEventId(transportEventId),
                new SourceMessageId(sourceMessageId),
                new SessionSourceRef("slack", "channel-1:" + threadId),
                new ParticipantRef("slack", "user-1"),
                sourceText,
                "question for " + sourceMessageId,
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace-1", "channel-1", sourceMessageId, threadId, "user-1", sourceText),
                RECEIVED_AT);
    }

    private long rowCount(String tableName) {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM " + tableName).query(Long.class).single();
        return count;
    }

    private long receiptCount() {
        Long count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM delivery_outbox
                WHERE delivery_kind = 'RECEIPT'
                  AND status = 'PENDING'
                  AND response_text = '已接收'
                """).query(Long.class).single();
        return count;
    }

    private static final class Identities implements InboxIdentityGenerator {

        private final String prefix;
        private int inboxSequence;
        private int sessionSequence;
        private int runSequence;
        private int deliverySequence;
        private int conflictSequence;

        private Identities(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public InboxMessageId nextInboxMessageId() {
            inboxSequence++;
            return new InboxMessageId(prefix + "-inbox-" + inboxSequence);
        }

        @Override
        public SessionId nextSessionId() {
            sessionSequence++;
            return new SessionId(prefix + "-session-" + sessionSequence);
        }

        @Override
        public AnalysisRunId nextRunId() {
            runSequence++;
            return new AnalysisRunId(prefix + "-run-" + runSequence);
        }

        @Override
        public String nextDeliveryId() {
            deliverySequence++;
            return prefix + "-delivery-" + deliverySequence;
        }

        @Override
        public String nextConflictId() {
            conflictSequence++;
            return prefix + "-conflict-" + conflictSequence;
        }
    }
}
