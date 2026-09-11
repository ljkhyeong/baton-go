package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
class LinkCodeKeyVersionMigrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME)
            .withUrlParam("connectTimeout", "3000").withUrlParam("socketTimeout", "30000");

    @Test
    @DisplayName("V6는 기존 생성 예약과 키 등록 정보를 기존 키 버전으로 보존한다")
    void preservesLegacyReservationAndKeyBinding() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("5").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE link_code_key_guard SET derivation_version = 'hmac-sha256-link-code-v1',
                        key_fingerprint = REPEAT('a', 64) WHERE guard_id = 1
                    """);
            statement.executeUpdate("""
                    INSERT INTO link_creation_requests (idempotency_key_hash, link_id, public_origin, created_at)
                    VALUES (REPEAT('b', 64), UUID_TO_BIN('00000000-0000-4000-8000-000000000001'),
                        'https://old-go.example', '2026-01-01 00:00:00.123456')
                    """);
        }
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("6").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT r.key_id, r.public_origin, k.derivation_version, k.key_fingerprint
                     FROM link_creation_requests r JOIN link_code_keys k ON k.key_id = r.key_id
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("key_id")).isEqualTo("legacy");
            assertThat(result.getString("public_origin")).isEqualTo("https://old-go.example");
            assertThat(result.getString("derivation_version")).isEqualTo("hmac-sha256-link-code-v1");
            assertThat(result.getString("key_fingerprint")).isEqualTo("a".repeat(64));
            assertThat(result.next()).isFalse();
        }
    }
}
