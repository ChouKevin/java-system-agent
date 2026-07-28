package com.java.system.agent.persistence.postgres;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL schema migration 的真實資料庫驗證
 */
class PostgresMigrationIT extends PostgresIntegrationTestSupport {

    private static final Set<String> DOMAIN_TABLES = Set.of(
            "agent_session",
            "session_inbox",
            "session_turn",
            "agent_run",
            "agent_run_event",
            "source_transport_event",
            "canonical_source_message",
            "source_event_conflict",
            "delivery_outbox");

    @Test
    void migratesEmptyDatabaseAndRemainsRepeatableAfterClean() throws SQLException {
        Flyway flyway = newFlyway();

        flyway.migrate();
        assertMigratedSchema(flyway);

        flyway.clean();
        flyway.migrate();
        assertMigratedSchema(flyway);
    }

    @Test
    void rejectsTheDirectCutoverWhenLegacyDomainRowsExist() {
        Flyway targetV1 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("1")
                .cleanDisabled(false)
                .load();
        targetV1.clean();
        targetV1.migrate();
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_session (
                        session_id, source_type, source_key, next_inbox_sequence, next_turn_sequence, created_at
                    ) VALUES (
                        'legacy-session', 'legacy', 'legacy-source', 0, 0, CURRENT_TIMESTAMP
                    )
                    """);
        } catch (SQLException exception) {
            throw new AssertionError("cannot prepare populated V1 schema", exception);
        }

        assertThatThrownBy(() -> newFlyway().migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("M3 direct cutover requires empty");
    }

    private void assertMigratedSchema(Flyway flyway) throws SQLException {
        assertThat(domainTableNames()).isEqualTo(DOMAIN_TABLES);

        MigrationInfo[] appliedMigrations = flyway.info().applied();
        assertThat(appliedMigrations).hasSize(2);
        assertThat(appliedMigrations[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(appliedMigrations[0].getDescription()).isEqualTo("create agent session inbox and trace");
        assertThat(appliedMigrations[0].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(appliedMigrations[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(appliedMigrations[1].getDescription()).isEqualTo("create slack source and delivery lifecycle");
        assertThat(appliedMigrations[1].getState()).isEqualTo(MigrationState.SUCCESS);
        assertColumnNullability("session_inbox", "source_text", false);
        assertColumnNullability("session_inbox", "question_text", false);
        assertColumnNullability("session_inbox", "participant_source_type", false);
        assertColumnNullability("session_inbox", "participant_key", false);
        assertColumnNullability("session_inbox", "defer_reason", true);
        assertColumnNullability("session_turn", "participant_source_type", false);
        assertColumnNullability("session_turn", "participant_key", false);
        assertColumnNullability("agent_run", "question_text", false);
        assertColumnAbsent("session_inbox", "exact_question");
        assertColumnAbsent("agent_run", "exact_question");
        assertConstraints("source_transport_event", "pk_source_transport_event", "ck_source_transport_event_fingerprint");
        assertConstraints("canonical_source_message", "pk_canonical_source_message",
                "ck_canonical_source_message_fingerprint");
        assertConstraints("source_event_conflict", "pk_source_event_conflict", "ck_source_event_conflict_scope",
                "ck_source_event_conflict_authoritative_fingerprint", "ck_source_event_conflict_incoming_fingerprint",
                "ck_source_event_conflict_failure_category", "uq_source_event_conflict_duplicate");
        assertConstraints("session_inbox", "fk_session_inbox_canonical_source", "uq_session_inbox_session_sequence");
        assertConstraints("delivery_outbox", "pk_delivery_outbox", "fk_delivery_outbox_inbox",
                "uq_delivery_outbox_inbox_kind", "ck_delivery_outbox_response_contract",
                "ck_delivery_outbox_waiting_final", "ck_delivery_outbox_provider_message",
                "ck_delivery_outbox_blocked_failure", "ck_delivery_outbox_failure_bounds");
        assertIndex("session_inbox", "ix_session_inbox_claim", false,
                "status", "available_at", "created_at", "session_sequence");
        assertIndex("delivery_outbox", "ix_delivery_outbox_claim", false,
                "status", "next_attempt_at", "created_at");
        assertIndex("session_inbox", "uq_session_inbox_single_processing", true, "status");
    }

    private void assertColumnNullability(String tableName, String columnName, boolean nullable) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
                ResultSet columns = databaseMetaData(connection).getColumns(null, "public", tableName, columnName)) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getInt("NULLABLE")).isEqualTo(
                    nullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);
        }
    }

    private void assertColumnAbsent(String tableName, String columnName) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
                ResultSet columns = databaseMetaData(connection).getColumns(null, "public", tableName, columnName)) {
            assertThat(columns.next()).isFalse();
        }
    }

    private void assertIndex(String tableName, String indexName, boolean unique, String... expectedColumns) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
                ResultSet indexes = databaseMetaData(connection).getIndexInfo(null, "public", tableName, false, false)) {
            Map<Short, String> columns = new TreeMap<>();
            Set<Boolean> uniqueness = new HashSet<>();
            while (indexes.next()) {
                if (indexName.equals(indexes.getString("INDEX_NAME"))) {
                    columns.put(indexes.getShort("ORDINAL_POSITION"), indexes.getString("COLUMN_NAME"));
                    uniqueness.add(indexes.getBoolean("NON_UNIQUE"));
                }
            }
            assertThat(columns.values()).containsExactly(expectedColumns);
            assertThat(uniqueness).containsExactly(!unique);
        }
    }

    private void assertConstraints(String tableName, String... expectedConstraintNames) throws SQLException {
        Set<String> constraintNames = new HashSet<>();
        try (Connection connection = POSTGRES.createConnection("");
                java.sql.PreparedStatement statement = connection.prepareStatement("""
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = ?::regclass
                        """)) {
            statement.setString(1, "public." + tableName);
            try (ResultSet constraints = statement.executeQuery()) {
                while (constraints.next()) {
                    constraintNames.add(constraints.getString("conname"));
                }
            }
        }
        assertThat(constraintNames).contains(expectedConstraintNames);
    }

    private Set<String> domainTableNames() throws SQLException {
        Set<String> tableNames = new HashSet<>();
        try (Connection connection = POSTGRES.createConnection("");
                ResultSet tables = databaseMetaData(connection).getTables(null, "public", "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (!"flyway_schema_history".equals(tableName)) {
                    tableNames.add(tableName);
                }
            }
        }
        return tableNames;
    }

    private DatabaseMetaData databaseMetaData(Connection connection) throws SQLException {
        return connection.getMetaData();
    }
}
