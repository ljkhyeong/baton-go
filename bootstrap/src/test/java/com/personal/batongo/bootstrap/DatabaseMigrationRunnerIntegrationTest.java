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
    private static final String LATEST_MIGRATION_VERSION = "7";

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
    @DisplayName("마이그레이션 전용 실행은 VERIFY_IDENTITY로 최신 스키마까지 적용한다")
    void migratesSchemaThroughVerifiedTls() throws SQLException {
        String jdbcUrl = MYSQL.verifiedJdbcUrl(trustedCaStore);
        String[] arguments = MYSQL.migrationArguments(jdbcUrl);

        BatonGoApplication.main(arguments);

        try (Connection connection = MYSQL.connectAsRuntime(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success = TRUE
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("version"))
                    .isEqualTo(LATEST_MIGRATION_VERSION);
        }
    }

    @Test
    @DisplayName("배포 초기화 스크립트는 실행 계정에 DML 권한만 부여한다")
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

    @Test
    @DisplayName("배포 MySQL은 비루트 사용자로 시작하고 Linux 권한을 모두 제거한다")
    void runsMySqlWithRestrictedContainerPermissions() throws Exception {
        var result = MYSQL.execInContainer(
                "sh",
                "-ec",
                "id -u; id -g; awk '/^CapEff:/ {print $2}' /proc/1/status"
        );

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout().lines()).containsExactly(
                "999",
                "999",
                "0000000000000000"
        );
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
