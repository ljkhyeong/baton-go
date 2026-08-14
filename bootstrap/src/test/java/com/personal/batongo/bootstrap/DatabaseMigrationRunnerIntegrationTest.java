package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.BatonGoApplication;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("mysql")
@Testcontainers
class DatabaseMigrationRunnerIntegrationTest {

    private static final String DATABASE = "baton_go";

    @Container
    static final DeploymentMySqlFixture MYSQL = new DeploymentMySqlFixture();

    @TempDir
    static Path truststoreDirectory;

    private static Path trustedCaStore;

    @BeforeAll
    static void createClientTruststores() throws Exception {
        trustedCaStore = MYSQL.createTruststore(
                truststoreDirectory.resolve("trusted-ca.p12")
        );
    }

    @Test
    @DisplayName("Spring Boot Flyway initializer는 VERIFY_IDENTITY로 최신 스키마를 적용한다")
    void migratesSchemaThroughVerifiedTls() throws SQLException {
        String jdbcUrl = MYSQL.verifiedJdbcUrl(trustedCaStore);
        String[] arguments = MYSQL.migrationArguments(jdbcUrl);

        BatonGoApplication.main(arguments);

        try (Connection connection = MYSQL.connectAsMigrator(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*) AS link_count,
                            @@GLOBAL.require_secure_transport AS secure_transport
                     FROM smart_links
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("link_count")).isZero();
            assertThat(resultSet.getBoolean("secure_transport")).isTrue();
        }
    }

    @Test
    @DisplayName("배포 init script는 runtime 계정에 정확한 DML 권한만 부여한다")
    void createsRuntimeUserWithOnlyDataManipulationPrivileges() throws SQLException {
        String jdbcUrl = MYSQL.verifiedJdbcUrl(trustedCaStore);

        try (Connection runtimeConnection = MYSQL.connectAsRuntime(jdbcUrl);
             Statement runtimeStatement = runtimeConnection.createStatement()) {
            assertThat(schemaPrivileges(runtimeConnection)).containsExactlyInAnyOrder(
                    "SELECT",
                    "INSERT",
                    "UPDATE",
                    "DELETE"
            );
            assertThatThrownBy(() -> runtimeStatement.executeUpdate("""
                    CREATE TABLE runtime_ddl_probe (
                        id BIGINT NOT NULL PRIMARY KEY
                    )
                    """))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static Set<String> schemaPrivileges(Connection runtimeConnection)
            throws SQLException {
        Set<String> privileges = new LinkedHashSet<>();
        try (PreparedStatement statement = runtimeConnection.prepareStatement("""
                SELECT PRIVILEGE_TYPE
                FROM INFORMATION_SCHEMA.SCHEMA_PRIVILEGES
                WHERE TABLE_SCHEMA = ?
                """)) {
            statement.setString(1, DATABASE);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    privileges.add(resultSet.getString(1));
                }
            }
        }
        return privileges;
    }

}
