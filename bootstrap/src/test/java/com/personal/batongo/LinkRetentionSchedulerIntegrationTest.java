package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 자동 정리를 켠 전체 애플리케이션이 주기 실행과 트랜잭션 경계까지 실제 빈으로 동작하는지 확인한다. */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "baton-go.link-retention.enabled=true",
        "baton-go.link-retention.period=30d",
        "baton-go.link-retention.interval=1s"
})
@TestPropertySource("mysql-it.properties")
class LinkRetentionSchedulerIntegrationTest {

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = MySqlTestImage.container();

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("자동 정리를 켜면 보존 기간이 지난 종료 링크를 주기적으로 지우고 예약에 정리 표시를 남긴다")
    void purgesRetiredLinkOnSchedule() {
        String linkId = "b5d8f0c2-7f3e-4a57-9b52-0d7a3e1c9a11";
        jdbc.update("""
                INSERT INTO smart_links (id, code_hash, target_system, target_path, purpose, expires_at, created_at)
                VALUES (UUID_TO_BIN(?), REPEAT('a', 64), 'ROUND', '/room/abcd-efgh-jkmn', 'MEETING_ENTRY',
                        TIMESTAMP('2020-01-02'), TIMESTAMP('2020-01-01'))
                """, linkId);
        jdbc.update("""
                INSERT INTO link_creation_requests (
                    idempotency_key_hash, link_id, public_origin, key_id, request_hash, created_at
                ) VALUES (REPEAT('b', 64), UUID_TO_BIN(?), 'https://go.example', 'default', REPEAT('c', 64),
                          TIMESTAMP('2020-01-01'))
                """, linkId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM smart_links WHERE id = UUID_TO_BIN(?)", Long.class, linkId
        )).isZero());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM link_creation_requests WHERE purged_at IS NOT NULL AND public_origin IS NULL",
                Long.class
        )).isEqualTo(1);
    }
}
