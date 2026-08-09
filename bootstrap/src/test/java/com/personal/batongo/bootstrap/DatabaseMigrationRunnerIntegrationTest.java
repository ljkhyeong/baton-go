package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.BatonGoApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("mysql")
@Testcontainers
class DatabaseMigrationRunnerIntegrationTest {

    private static final String DATABASE = "baton_go";

    @Container
    static final DeploymentMySqlFixture MYSQL = new DeploymentMySqlFixture();

    private static Path truststoreDirectory;
    private static Path trustedCaStore;
    private static Path untrustedCaStore;

    @BeforeAll
    static void createClientTruststores() throws Exception {
        truststoreDirectory = Files.createTempDirectory("baton-go-mysql-tls-");
        trustedCaStore = MYSQL.createTruststore(
                "/etc/mysql/tls/ca.pem",
                truststoreDirectory.resolve("trusted-ca.p12")
        );
        untrustedCaStore = MYSQL.createTruststore(
                "/etc/mysql/tls/wrong-ca.pem",
                truststoreDirectory.resolve("untrusted-ca.p12")
        );
    }

    @AfterAll
    static void deleteClientTruststores() throws IOException {
        if (trustedCaStore != null) {
            Files.deleteIfExists(trustedCaStore);
        }
        if (untrustedCaStore != null) {
            Files.deleteIfExists(untrustedCaStore);
        }
        if (truststoreDirectory != null) {
            Files.deleteIfExists(truststoreDirectory);
        }
    }

    @Test
    @DisplayName("배포 preflight는 공식 entrypoint 전에 통과하고 원격 root 계정을 남기지 않는다")
    void executesDeploymentPreflightAndKeepsRootLocalOnly()
            throws IOException, InterruptedException {
        assertThat(MYSQL.getLogs())
                .contains("mysql encrypted private key negative preflight passed")
                .contains("mysql mismatched private key negative preflight passed")
                .contains("mysql bootstrap preflight passed");

        ExecResult remoteRootCount = MYSQL.execInContainer(
                "/bin/sh",
                "-c",
                """
                MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" \
                mysql --protocol=SOCKET --user=root --batch --skip-column-names \
                  --execute="SELECT COUNT(*) FROM mysql.user WHERE user='root' AND host='%';"
                """
        );
        assertThat(remoteRootCount.getExitCode()).isZero();
        assertThat(remoteRootCount.getStdout()).isEqualTo("0\n");
    }

    @Test
    @DisplayName("migration-only 실행은 VERIFY_IDENTITY로 최신 스키마를 적용하고 반복 실행한다")
    void migratesSchemaThroughVerifiedTlsAndReturns() throws SQLException {
        String jdbcUrl = MYSQL.verifiedJdbcUrl(
                DeploymentMySqlFixture.VERIFIED_HOST,
                trustedCaStore
        );
        String[] arguments = MYSQL.migrationArguments(jdbcUrl);

        BatonGoApplication.main(arguments);
        BatonGoApplication.main(arguments);

        try (Connection connection = MYSQL.connectAsMigrator(jdbcUrl)) {
            assertSecureTransport(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("""
                         SELECT COUNT(*)
                         FROM flyway_schema_history
                         WHERE success = 1
                         """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isPositive();
            }
        }
    }

    @Test
    @DisplayName("배포 init script는 runtime 계정에 정확한 DML 권한만 부여한다")
    void createsRuntimeUserWithOnlyDataManipulationPrivileges() throws SQLException {
        String jdbcUrl = MYSQL.verifiedJdbcUrl(
                DeploymentMySqlFixture.VERIFIED_HOST,
                trustedCaStore
        );

        try (Connection migrationConnection = MYSQL.connectAsMigrator(jdbcUrl);
             Statement migrationStatement = migrationConnection.createStatement()) {
            migrationStatement.executeUpdate("DROP TABLE IF EXISTS runtime_dml_probe");
            migrationStatement.executeUpdate("DROP TABLE IF EXISTS runtime_ddl_probe");
            migrationStatement.executeUpdate("""
                    CREATE TABLE runtime_dml_probe (
                        id BIGINT NOT NULL PRIMARY KEY,
                        probe_value VARCHAR(64) NOT NULL
                    )
                    """);

            try (Connection runtimeConnection = MYSQL.connectAsRuntime(jdbcUrl);
                 Statement runtimeStatement = runtimeConnection.createStatement()) {
                assertSecureTransport(runtimeConnection);
                assertThat(schemaPrivileges(runtimeConnection)).containsExactlyInAnyOrder(
                        "SELECT",
                        "INSERT",
                        "UPDATE",
                        "DELETE"
                );

                assertThat(runtimeStatement.executeUpdate("""
                        INSERT INTO runtime_dml_probe (id, probe_value)
                        VALUES (1, 'created')
                        """)).isOne();
                try (ResultSet selected = runtimeStatement.executeQuery("""
                        SELECT probe_value
                        FROM runtime_dml_probe
                        WHERE id = 1
                        """)) {
                    assertThat(selected.next()).isTrue();
                    assertThat(selected.getString(1)).isEqualTo("created");
                }
                assertThat(runtimeStatement.executeUpdate("""
                        UPDATE runtime_dml_probe
                        SET probe_value = 'updated'
                        WHERE id = 1
                        """)).isOne();
                assertThat(runtimeStatement.executeUpdate("""
                        DELETE FROM runtime_dml_probe
                        WHERE id = 1
                        """)).isOne();

                assertThatThrownBy(() -> runtimeStatement.executeUpdate("""
                        CREATE TABLE runtime_ddl_probe (
                            id BIGINT NOT NULL PRIMARY KEY
                        )
                        """))
                        .isInstanceOfSatisfying(SQLException.class, exception ->
                                assertThat(exception.getErrorCode()).isEqualTo(1142)
                        );
            } finally {
                assertThat(tableExists(migrationConnection, "runtime_ddl_probe")).isFalse();
                migrationStatement.executeUpdate("DROP TABLE IF EXISTS runtime_dml_probe");
                migrationStatement.executeUpdate("DROP TABLE IF EXISTS runtime_ddl_probe");
            }
        }
    }

    @Test
    @DisplayName("MySQL은 sslMode가 DISABLED인 TCP 연결을 거부한다")
    void rejectsConnectionWhenTlsIsDisabled() throws SQLException {
        try (Connection control = MYSQL.connectAsMigrator(MYSQL.verifiedJdbcUrl(
                DeploymentMySqlFixture.VERIFIED_HOST,
                trustedCaStore
        ))) {
            assertSecureTransport(control);
        }

        assertThatThrownBy(() -> {
            try (Connection ignored = MYSQL.connectAsMigrator(MYSQL.disabledTlsJdbcUrl())) {
                // 연결되면 require_secure_transport 운영 계약 위반입니다.
            }
        }).isInstanceOfSatisfying(SQLException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(3159)
        );
    }

    @Test
    @DisplayName("VERIFY_IDENTITY는 신뢰하지 않는 CA의 서버 인증서를 거부한다")
    void rejectsServerCertificateFromUntrustedCa() throws SQLException {
        try (Connection control = MYSQL.connectAsMigrator(MYSQL.verifiedJdbcUrl(
                DeploymentMySqlFixture.VERIFIED_HOST,
                trustedCaStore
        ))) {
            assertSecureTransport(control);
        }

        assertTlsConnectionFailure(MYSQL.verifiedJdbcUrl(
                DeploymentMySqlFixture.VERIFIED_HOST,
                untrustedCaStore
        ));
    }

    @Test
    @DisplayName("VERIFY_IDENTITY는 인증서 SAN에 없는 MySQL hostname을 거부한다")
    void rejectsHostnameMissingFromCertificateSubjectAlternativeNames() throws SQLException {
        try (Connection control = MYSQL.connectAsMigrator(MYSQL.verifiedCaJdbcUrl(
                DeploymentMySqlFixture.INVALID_HOST,
                trustedCaStore
        ))) {
            assertSecureTransport(control);
        }

        assertTlsConnectionFailure(MYSQL.verifiedJdbcUrl(
                DeploymentMySqlFixture.INVALID_HOST,
                trustedCaStore
        ));
    }

    private static void assertSecureTransport(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet cipher = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            assertThat(cipher.next()).isTrue();
            assertThat(cipher.getString("Value")).isNotBlank();
        }
        try (Statement statement = connection.createStatement();
             ResultSet secureTransport = statement.executeQuery(
                     "SELECT @@GLOBAL.require_secure_transport"
             )) {
            assertThat(secureTransport.next()).isTrue();
            assertThat(secureTransport.getBoolean(1)).isTrue();
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

    private static boolean tableExists(Connection connection, String tableName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = ?
                """)) {
            statement.setString(1, DATABASE);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private static void assertTlsConnectionFailure(String jdbcUrl) {
        assertThatThrownBy(() -> {
            try (Connection ignored = MYSQL.connectAsMigrator(jdbcUrl)) {
                // 연결되면 VERIFY_IDENTITY 운영 계약 위반입니다.
            }
        }).isInstanceOfSatisfying(SQLException.class, exception ->
                assertThat(exception.getSQLState()).startsWith("08")
        );
    }
}
