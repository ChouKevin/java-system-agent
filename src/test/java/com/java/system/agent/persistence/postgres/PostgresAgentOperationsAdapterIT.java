package com.java.system.agent.persistence.postgres;

import com.java.system.agent.interaction.domain.NormalizedSourceEvent;
import com.java.system.agent.interaction.domain.SessionSourceRef;
import com.java.system.agent.interaction.domain.SourceMessageId;
import com.java.system.agent.interaction.domain.SourceAdmission;
import com.java.system.agent.interaction.domain.SourcePayloadFingerprintV1;
import com.java.system.agent.interaction.domain.TransportEventId;
import com.java.system.agent.interaction.domain.delivery.DeliveryStatus;
import com.java.system.agent.persistence.jdbc.PostgresAgentOperationsAdapter;
import com.java.system.agent.persistence.jdbc.PostgresSourceAcceptanceAdapter;
import com.java.system.agent.persistence.jdbc.UuidInboxIdentityGenerator;
import com.java.system.agent.answering.domain.conversation.ParticipantRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL durable operations 年齡 query 的真實資料庫整合測試
 */
class PostgresAgentOperationsAdapterIT extends PostgresIntegrationTestSupport {

    private static final Instant OBSERVED_AT = Instant.parse("2030-07-28T10:00:00Z");

    private JdbcClient jdbcClient;
    private PostgresSourceAcceptanceAdapter acceptance;
    private PostgresAgentOperationsAdapter operations;

    @BeforeEach
    void resetSchema() {
        newFlyway().clean();
        newFlyway().migrate();
        jdbcClient = JdbcClient.create(newDataSource());
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(newDataSource()));
        acceptance = new PostgresSourceAcceptanceAdapter(jdbcClient, transactionTemplate, new UuidInboxIdentityGenerator());
        operations = new PostgresAgentOperationsAdapter(jdbcClient);
    }

    @Test
    void readsOldestEligibleInboxAndEachDurableDeliveryStateAtTheSuppliedInstant() {
        SourceAdmission first = admit("one");
        SourceAdmission second = admit("two");
        jdbcClient.sql("""
                UPDATE session_inbox
                SET created_at = :oldest
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("oldest", Timestamp.from(OBSERVED_AT.minus(Duration.ofMinutes(10))))
                .param("inboxMessageId", first.inboxMessageId().value())
                .update();
        jdbcClient.sql("""
                UPDATE session_inbox
                SET created_at = :newer
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("newer", Timestamp.from(OBSERVED_AT.minus(Duration.ofMinutes(2))))
                .param("inboxMessageId", second.inboxMessageId().value())
                .update();
        jdbcClient.sql("""
                UPDATE delivery_outbox
                SET status = 'DELIVERED', provider_message_id = 'provider-one',
                    created_at = :oldest
                WHERE inbox_message_id = (
                    SELECT inbox_message_id FROM session_inbox WHERE inbox_message_id = :inboxMessageId)
                """)
                .param("oldest", Timestamp.from(OBSERVED_AT.minus(Duration.ofMinutes(9))))
                .param("inboxMessageId", first.inboxMessageId().value())
                .update();
        jdbcClient.sql("""
                UPDATE delivery_outbox
                SET created_at = :pendingCreated
                WHERE inbox_message_id = (
                    SELECT inbox_message_id FROM session_inbox WHERE inbox_message_id = :inboxMessageId)
                """)
                .param("pendingCreated", Timestamp.from(OBSERVED_AT.minus(Duration.ofMinutes(4))))
                .param("inboxMessageId", second.inboxMessageId().value())
                .update();

        assertThat(operations.readDurableOperations(OBSERVED_AT).oldestEligibleInboxAge())
                .contains(Duration.ofMinutes(10));
        assertThat(operations.readDurableOperations(OBSERVED_AT).oldestDeliveryAgeByStatus().get(DeliveryStatus.DELIVERED))
                .contains(Duration.ofMinutes(9));
        assertThat(operations.readDurableOperations(OBSERVED_AT).oldestDeliveryAgeByStatus().get(DeliveryStatus.PENDING))
                .contains(Duration.ofMinutes(4));
        assertThat(operations.readDurableOperations(OBSERVED_AT).oldestDeliveryAgeByStatus().get(DeliveryStatus.BLOCKED))
                .isEmpty();
    }

    @Test
    void excludesInboxAgeWhenAnyInboxMessageIsProcessingGlobally() {
        SourceAdmission admitted = admit("processing");
        jdbcClient.sql("""
                UPDATE session_inbox
                SET status = 'PROCESSING', attempt_count = 1, claimed_at = :observedAt
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("observedAt", Timestamp.from(OBSERVED_AT))
                .param("inboxMessageId", admitted.inboxMessageId().value())
                .update();

        assertThat(operations.readDurableOperations(OBSERVED_AT).oldestEligibleInboxAge()).isEmpty();
    }

    @Test
    void excludesLaterSameSessionInboxBehindAnEarlierPendingMessageThatIsNotYetEligible() {
        SourceAdmission first = admitInSession("head", "shared-thread");
        SourceAdmission later = admitInSession("later", "shared-thread");
        jdbcClient.sql("""
                UPDATE session_inbox
                SET available_at = :future
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("future", Timestamp.from(OBSERVED_AT.plus(Duration.ofMinutes(1))))
                .param("inboxMessageId", first.inboxMessageId().value())
                .update();
        jdbcClient.sql("""
                UPDATE session_inbox
                SET created_at = :oldest
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("oldest", Timestamp.from(OBSERVED_AT.minus(Duration.ofMinutes(10))))
                .param("inboxMessageId", later.inboxMessageId().value())
                .update();

        assertThat(operations.readDurableOperations(OBSERVED_AT).oldestEligibleInboxAge()).isEmpty();
    }

    @Test
    void readsAnEligibleHeadFromAnotherSessionWhenASeparatedSessionIsBlocked() {
        SourceAdmission first = admitInSession("head", "blocked-thread");
        SourceAdmission later = admitInSession("later", "blocked-thread");
        SourceAdmission eligible = admitInSession("eligible", "available-thread");
        jdbcClient.sql("""
                UPDATE session_inbox
                SET available_at = :future
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("future", Timestamp.from(OBSERVED_AT.plus(Duration.ofMinutes(1))))
                .param("inboxMessageId", first.inboxMessageId().value())
                .update();
        jdbcClient.sql("""
                UPDATE session_inbox
                SET created_at = :oldest
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("oldest", Timestamp.from(OBSERVED_AT.minus(Duration.ofMinutes(12))))
                .param("inboxMessageId", later.inboxMessageId().value())
                .update();
        jdbcClient.sql("""
                UPDATE session_inbox
                SET created_at = :eligibleCreated
                WHERE inbox_message_id = :inboxMessageId
                """)
                .param("eligibleCreated", Timestamp.from(OBSERVED_AT.minus(Duration.ofMinutes(3))))
                .param("inboxMessageId", eligible.inboxMessageId().value())
                .update();

        assertThat(operations.readDurableOperations(OBSERVED_AT).oldestEligibleInboxAge())
                .contains(Duration.ofMinutes(3));
    }

    private SourceAdmission admit(String suffix) {
        return admitInSession(suffix, "thread-" + suffix);
    }

    private SourceAdmission admitInSession(String suffix, String thread) {
        String sourceText = "<@agent> question " + suffix;
        return acceptance.accept(new NormalizedSourceEvent(
                "slack",
                new TransportEventId("event-" + suffix),
                new SourceMessageId("source-" + suffix),
                new SessionSourceRef("slack", "channel:" + thread),
                new ParticipantRef("slack", "participant-" + suffix),
                sourceText,
                "question " + suffix,
                SourcePayloadFingerprintV1.fromCanonicalFields(
                        "workspace", "channel", "message-" + suffix, "thread-" + suffix,
                        "participant-" + suffix, sourceText),
                OBSERVED_AT)).admission().orElseThrow();
    }
}
