package com.java.system.agent.persistence.postgres;

import com.java.system.agent.inbox.domain.InboxClaim;
import com.java.system.agent.inbox.domain.InboxDeferReason;
import com.java.system.agent.inbox.domain.InboxFailure;
import com.java.system.agent.inbox.domain.InboxMessage;
import com.java.system.agent.inbox.domain.InboxMessageId;
import com.java.system.agent.inbox.domain.NormalizedSourceEvent;
import com.java.system.agent.inbox.domain.SessionSourceRef;
import com.java.system.agent.inbox.domain.SourceAdmission;
import com.java.system.agent.inbox.domain.SourceMessageId;
import com.java.system.agent.inbox.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.inbox.domain.TransportEventId;
import com.java.system.agent.inbox.port.out.InboxIdentityGenerator;
import com.java.system.agent.persistence.jdbc.PostgresSessionInboxAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSourceAcceptanceAdapter;
import com.java.system.agent.persistence.jdbc.InboxPersistenceConflictException;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL M3 inbox global claim、延後與 receipt 衍生 final delivery 的整合測試
 */
class PostgresSessionInboxAdapterIT extends PostgresIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2030-07-26T10:00:00Z");
    private JdbcClient jdbcClient;
    private PostgresSessionInboxAdapter inbox;
    private PostgresSourceAcceptanceAdapter acceptance;
    private DataSource dataSource;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        dataSource = newDataSource();
        jdbcClient = JdbcClient.create(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Identities identities = new Identities();
        inbox = new PostgresSessionInboxAdapter(jdbcClient, transactionTemplate, identities);
        acceptance = new PostgresSourceAcceptanceAdapter(jdbcClient, transactionTemplate, identities);
    }

    @Test
    void claimsOnlyTheOldestSessionHeadAndEnforcesOneGlobalProcessingMessage() {
        admit("event-1", "message-1", "thread-1", "first");
        admit("event-2", "message-2", "thread-1", "follower");
        InboxClaim first = inbox.claimNext(NOW).orElseThrow();

        assertThat(first.message().questionText()).isEqualTo("question message-1");
        assertThat(first.message().attemptCount()).isEqualTo(1);
        assertThat(inbox.claimNext(NOW)).isEmpty();

        inbox.completeWithFinal(first, result(first.message().runId()), NOW);
        InboxClaim follower = inbox.claimNext(NOW).orElseThrow();
        assertThat(follower.message().sourceMessageId()).isEqualTo(new SourceMessageId("message-2"));
    }

    @Test
    void concurrentGlobalClaimsReturnAtMostOneProcessingMessage() throws Exception {
        admit("event-1", "message-1", "thread-1", "first");
        admit("event-2", "message-2", "thread-2", "other");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<InboxClaim>> first = executor.submit(() -> inbox.claimNext(NOW));
            Future<Optional<InboxClaim>> second = executor.submit(() -> independentInbox().claimNext(NOW));
            List<Optional<InboxClaim>> claims = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(inbox.claimNext(NOW)).isEmpty();
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("global claim executor did not terminate");
            }
        }
    }

    @Test
    void preservesCapacityDeferralAcrossRecoveryAndDoesNotIncrementItsAttempt() {
        admit("event-1", "message-1", "thread-1", "first");
        InboxClaim first = inbox.claimNext(NOW).orElseThrow();

        inbox.deferForCapacity(first, NOW.plusSeconds(30));
        assertThat(inbox.claimNext(NOW.plusSeconds(1))).isEmpty();
        InboxClaim resumed = inbox.claimNext(NOW.plusSeconds(30)).orElseThrow();
        assertThat(resumed.message().attemptCount()).isEqualTo(1);
        assertThat(resumed.message().deferReason()).contains(InboxDeferReason.MODEL_CAPACITY);

        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(31))).isEqualTo(1);
        InboxClaim recovered = inbox.claimNext(NOW.plusSeconds(31)).orElseThrow();
        assertThat(recovered.message().attemptCount()).isEqualTo(1);
        assertThat(recovered.message().deferReason()).contains(InboxDeferReason.MODEL_CAPACITY);
    }

    @Test
    void recoversOnlyTheFailedClaimAndPermitsInProcessProgress() {
        admit("event-1", "message-1", "thread-1", "first");
        InboxClaim failedClaim = inbox.claimNext(NOW).orElseThrow();

        assertThat(inbox.recoverClaim(failedClaim, NOW.plusSeconds(1))).isTrue();

        InboxClaim resumed = inbox.claimNext(NOW.plusSeconds(1)).orElseThrow();
        assertThat(resumed.message().inboxMessageId()).isEqualTo(failedClaim.message().inboxMessageId());
        assertThat(resumed.message().attemptCount()).isEqualTo(2);
        inbox.completeWithFinal(resumed, result(resumed.message().runId()), NOW.plusSeconds(1));
        assertThat(inbox.claimNext(NOW.plusSeconds(1))).isEmpty();
    }

    @Test
    void createsFinalDeliveryFromTheLockedReceiptState() {
        SourceAdmission pendingAdmission = admit("event-1", "message-1", "thread-1", "pending");
        InboxClaim pendingClaim = inbox.claimNext(NOW).orElseThrow();
        inbox.completeWithFinal(pendingClaim, result(pendingClaim.message().runId()), NOW);
        assertThat(finalStatus(pendingAdmission)).isEqualTo("WAITING_FOR_RECEIPT");

        SourceAdmission deliveredAdmission = admit("event-2", "message-2", "thread-2", "delivered");
        markReceipt(deliveredAdmission, "DELIVERED");
        InboxClaim deliveredClaim = inbox.claimNext(NOW).orElseThrow();
        inbox.completeWithFinal(deliveredClaim, result(deliveredClaim.message().runId()), NOW);
        assertThat(finalStatus(deliveredAdmission)).isEqualTo("PENDING");

        SourceAdmission blockedAdmission = admit("event-3", "message-3", "thread-3", "blocked");
        markReceipt(blockedAdmission, "BLOCKED");
        InboxClaim blockedClaim = inbox.claimNext(NOW).orElseThrow();
        inbox.completeWithFinal(blockedClaim, result(blockedClaim.message().runId()), NOW);
        assertThat(finalStatus(blockedAdmission)).isEqualTo("BLOCKED");
        assertThat(finalFailure(blockedAdmission)).isEqualTo("PREDECESSOR_BLOCKED");
    }

    @Test
    void rejectsStaleTransitionsAndRecoveryKeepsFailureAndAttemptMetadata() {
        admit("event-1", "message-1", "thread-1", "first");
        InboxClaim first = inbox.claimNext(NOW).orElseThrow();
        InboxClaim stale = new InboxClaim(copyWithAttempt(first.message(), 2));

        assertThatThrownBy(() -> inbox.completeWithFinal(stale, result(stale.message().runId()), NOW))
                .isInstanceOf(InboxPersistenceConflictException.class);
        assertThatThrownBy(() -> inbox.retry(stale, new InboxFailure("STALE", "stale retry"), NOW))
                .isInstanceOf(InboxPersistenceConflictException.class);
        assertThatThrownBy(() -> inbox.failWithFinal(
                stale, new InboxFailure("STALE", "stale failure"), "safe", NOW))
                .isInstanceOf(InboxPersistenceConflictException.class);

        InboxFailure retryFailure = new InboxFailure("TEMPORARY", "retry later");
        inbox.retry(first, retryFailure, NOW);
        InboxClaim interrupted = inbox.claimNext(NOW).orElseThrow();
        assertThat(inbox.recoverInterrupted(NOW.plusSeconds(5))).isEqualTo(1);

        assertThat(stored(interrupted.message().inboxMessageId())).isEqualTo(new StoredInbox(
                "PENDING", interrupted.message().attemptCount(), Optional.empty(), NOW.plusSeconds(5),
                Optional.of(retryFailure.code()), Optional.of(retryFailure.description())));
    }

    private SourceAdmission admit(String eventId, String messageId, String threadId, String sourceText) {
        NormalizedSourceEvent event = new NormalizedSourceEvent(
                "slack", new TransportEventId(eventId), new SourceMessageId(messageId),
                new SessionSourceRef("slack", "channel-1:" + threadId), new ParticipantRef("slack", "U123456"),
                sourceText, "question " + messageId, SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace-1", "channel-1", messageId, threadId, "U123456", sourceText), NOW);
        return acceptance.accept(event).admission().orElseThrow();
    }

    private AnswerQuestionResult result(AnalysisRunId runId) {
        return new AnswerQuestionResult(
                runId, RunOutcome.INCONCLUSIVE, "資訊不足", Optional.empty(), RunResponseKind.RUNTIME_NOTICE,
                Optional.empty(), RevisionVector.empty());
    }

    private void markReceipt(SourceAdmission admission, String status) {
        jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = :status, provider_message_id = CASE WHEN :status = 'DELIVERED' THEN 'provider-1' ELSE NULL END,
                    last_failure_category = CASE WHEN :status = 'BLOCKED' THEN 'SLACK_DENIED' ELSE NULL END,
                    last_failure_description = CASE WHEN :status = 'BLOCKED' THEN 'delivery denied' ELSE NULL END
                WHERE inbox_message_id = :inboxMessageId
                  AND delivery_kind = 'RECEIPT'
                """)
                .param("status", status)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .update();
    }

    private String finalStatus(SourceAdmission admission) {
        return jdbcClient.sql("""
                SELECT status FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId AND delivery_kind = 'FINAL_RESPONSE'
                """)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .query(String.class)
                .single();
    }

    private String finalFailure(SourceAdmission admission) {
        return jdbcClient.sql("""
                SELECT last_failure_category FROM delivery_outbox
                WHERE inbox_message_id = :inboxMessageId AND delivery_kind = 'FINAL_RESPONSE'
                """)
                .param("inboxMessageId", admission.inboxMessageId().value())
                .query(String.class)
                .single();
    }

    private PostgresSessionInboxAdapter independentInbox() {
        return new PostgresSessionInboxAdapter(
                JdbcClient.create(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new Identities());
    }

    private InboxMessage copyWithAttempt(InboxMessage message, int attemptCount) {
        return new InboxMessage(
                message.inboxMessageId(), message.source(), message.sourceMessageId(), message.sessionId(),
                message.sessionSequence(), message.runId(), message.participant(), message.sourceText(), message.questionText(),
                message.status(), attemptCount, message.availableAt(), message.claimedAt(),
                message.deferReason(), message.lastFailure());
    }

    private StoredInbox stored(InboxMessageId inboxMessageId) {
        return jdbcClient.sql("""
                SELECT status, attempt_count, claimed_at, available_at, last_error_code, last_error_description
                FROM session_inbox WHERE inbox_message_id = :inboxMessageId
                """)
                .param("inboxMessageId", inboxMessageId.value())
                .query((resultSet, rowNumber) -> new StoredInbox(
                        resultSet.getString("status"), resultSet.getInt("attempt_count"),
                        Optional.ofNullable(resultSet.getTimestamp("claimed_at")),
                        resultSet.getTimestamp("available_at").toInstant(),
                        Optional.ofNullable(resultSet.getString("last_error_code")),
                        Optional.ofNullable(resultSet.getString("last_error_description"))))
                .single();
    }

    private record StoredInbox(
            String status,
            int attemptCount,
            Optional<java.sql.Timestamp> claimedAt,
            Instant availableAt,
            Optional<String> failureCode,
            Optional<String> failureDescription) {
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
