package com.java.system.agent.persistence.postgres;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL schema migration 的真實資料庫驗證
 */
class PostgresMigrationIT extends PostgresIntegrationTestSupport {

    private static final Set<String> DOMAIN_TABLES = Set.of(
            "agent_session",
            "session_inbox",
            "session_turn",
            "agent_run",
            "agent_run_event");

    @Test
    void migratesEmptyDatabaseAndRemainsRepeatableAfterClean() throws SQLException {
        Flyway flyway = newFlyway();

        flyway.migrate();
        assertMigratedSchema(flyway);

        flyway.clean();
        flyway.migrate();
        assertMigratedSchema(flyway);
    }

    private void assertMigratedSchema(Flyway flyway) throws SQLException {
        assertThat(domainTableNames()).isEqualTo(DOMAIN_TABLES);

        MigrationInfo[] appliedMigrations = flyway.info().applied();
        assertThat(appliedMigrations).hasSize(1);
        assertThat(appliedMigrations[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(appliedMigrations[0].getDescription()).isEqualTo("create agent session inbox and trace");
        assertThat(appliedMigrations[0].getState()).isEqualTo(MigrationState.SUCCESS);
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
