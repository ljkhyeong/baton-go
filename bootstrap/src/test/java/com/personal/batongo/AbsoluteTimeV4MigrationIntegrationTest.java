package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
class AbsoluteTimeV4MigrationIntegrationTest {

    private static final String NON_UTC_TIME_ZONE = "+09:00";
    private static final String LINK_ID = "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final Instant NOT_BEFORE =
            Instant.parse("2026-01-15T01:02:04.234567Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-01-16T05:06:07.345678Z");
    private static final Instant REVOKED_AT =
            Instant.parse("2026-01-15T02:03:04.456789Z");
    private static final Instant CREATED_AT =
            Instant.parse("2026-01-15T01:02:03.123456Z");
    private static final Instant REQUEST_CREATED_AT =
            Instant.parse("2026-01-15T01:02:05.567890Z");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

    @Test
    @DisplayName("V4는 값이 채워진 V3의 다섯 절대 시각을 UTC 그대로 옮기고 세션 시간대를 복원한다")
    void upgradesPopulatedV3WithoutShiftingUtcTimesOrSessionTimeZone() throws SQLException {
        migrateToVersionThree();
        seedAllTimestampColumnsInUtc();

        SessionTimeZoneCallback sessionTimeZoneCallback = new SessionTimeZoneCallback();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("4")
                .initSql("SET SESSION time_zone = '" + NON_UTC_TIME_ZONE + "'")
                .callbacks(sessionTimeZoneCallback)
                .load()
                .migrate();

        assertThat(sessionTimeZoneCallback.beforeV4()).isEqualTo(NON_UTC_TIME_ZONE);
        assertThat(sessionTimeZoneCallback.afterV4()).isEqualTo(NON_UTC_TIME_ZONE);

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION time_zone = '" + NON_UTC_TIME_ZONE + "'");

            assertThat(readAbsoluteTimes(connection)).isEqualTo(new AbsoluteTimes(
                    NOT_BEFORE,
                    EXPIRES_AT,
                    REVOKED_AT,
                    CREATED_AT,
                    REQUEST_CREATED_AT
            ));
            assertThat(readTimeColumns(connection)).containsExactlyInAnyOrder(
                    new TimeColumn("smart_links", "not_before", "datetime", 6, "YES"),
                    new TimeColumn("smart_links", "expires_at", "datetime", 6, "YES"),
                    new TimeColumn("smart_links", "revoked_at", "datetime", 6, "YES"),
                    new TimeColumn("smart_links", "created_at", "datetime", 6, "NO"),
                    new TimeColumn(
                            "link_creation_requests",
                            "created_at",
                            "datetime",
                            6,
                            "NO"
                    )
            );
        }
    }

    private void migrateToVersionThree() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("3")
                .load()
                .migrate();
    }

    private void seedAllTimestampColumnsInUtc() throws SQLException {
        try (Connection connection = connection();
             Statement timeZoneStatement = connection.createStatement()) {
            timeZoneStatement.execute("SET SESSION time_zone = '+00:00'");
            assertThat(readTimeColumns(connection))
                    .extracting(TimeColumn::dataType)
                    .containsOnly("timestamp");

            try (PreparedStatement link = connection.prepareStatement("""
                    INSERT INTO smart_links (
                        id,
                        code_hash,
                        target_system,
                        target_path,
                        purpose,
                        not_before,
                        expires_at,
                        revoked_at,
                        created_at,
                        version
                    ) VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """)) {
                link.setString(1, LINK_ID);
                link.setString(2, "a".repeat(64));
                link.setString(3, "BATON");
                link.setString(
                        4,
                        "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                                + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a"
                );
                link.setString(5, "NAVIGATION");
                link.setObject(6, LocalDateTime.ofInstant(NOT_BEFORE, ZoneOffset.UTC));
                link.setObject(7, LocalDateTime.ofInstant(EXPIRES_AT, ZoneOffset.UTC));
                link.setObject(8, LocalDateTime.ofInstant(REVOKED_AT, ZoneOffset.UTC));
                link.setObject(9, LocalDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
                link.executeUpdate();
            }

            try (PreparedStatement reservation = connection.prepareStatement("""
                    INSERT INTO link_creation_requests (
                        idempotency_key_hash,
                        link_id,
                        created_at
                    ) VALUES (?, UUID_TO_BIN(?), ?)
                    """)) {
                reservation.setString(1, "b".repeat(64));
                reservation.setString(2, LINK_ID);
                reservation.setObject(
                        3,
                        LocalDateTime.ofInstant(REQUEST_CREATED_AT, ZoneOffset.UTC)
                );
                reservation.executeUpdate();
            }

            assertThat(readAbsoluteTimes(connection)).isEqualTo(new AbsoluteTimes(
                    NOT_BEFORE,
                    EXPIRES_AT,
                    REVOKED_AT,
                    CREATED_AT,
                    REQUEST_CREATED_AT
            ));
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
    }

    private AbsoluteTimes readAbsoluteTimes(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DATE_FORMAT(link.not_before, '%Y-%m-%dT%H:%i:%s.%fZ') AS not_before,
                       DATE_FORMAT(link.expires_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS expires_at,
                       DATE_FORMAT(link.revoked_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS revoked_at,
                       DATE_FORMAT(link.created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at,
                       DATE_FORMAT(
                           creation_request.created_at,
                           '%Y-%m-%dT%H:%i:%s.%fZ'
                       ) AS request_created_at
                FROM smart_links link
                JOIN link_creation_requests creation_request
                  ON creation_request.link_id = link.id
                WHERE link.id = UUID_TO_BIN(?)
                """)) {
            statement.setString(1, LINK_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                AbsoluteTimes result = new AbsoluteTimes(
                        Instant.parse(resultSet.getString("not_before")),
                        Instant.parse(resultSet.getString("expires_at")),
                        Instant.parse(resultSet.getString("revoked_at")),
                        Instant.parse(resultSet.getString("created_at")),
                        Instant.parse(resultSet.getString("request_created_at"))
                );
                assertThat(resultSet.next()).isFalse();
                return result;
            }
        }
    }

    private List<TimeColumn> readTimeColumns(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name,
                       column_name,
                       data_type,
                       datetime_precision,
                       is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND (
                      (table_name = 'smart_links' AND column_name IN (
                          'not_before',
                          'expires_at',
                          'revoked_at',
                          'created_at'
                      ))
                      OR (table_name = 'link_creation_requests'
                          AND column_name = 'created_at')
                  )
                """);
             ResultSet resultSet = statement.executeQuery()) {
            List<TimeColumn> columns = new ArrayList<>();
            while (resultSet.next()) {
                columns.add(new TimeColumn(
                        resultSet.getString("table_name"),
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        resultSet.getInt("datetime_precision"),
                        resultSet.getString("is_nullable")
                ));
            }
            return columns;
        }
    }

    private static String readSessionTimeZone(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT @@SESSION.time_zone"
             )) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Flyway 세션 상태를 읽을 수 없습니다");
            }
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Flyway 세션 상태를 읽을 수 없습니다", exception);
        }
    }

    private static final class SessionTimeZoneCallback implements Callback {

        private String beforeV4;
        private String afterV4;

        @Override
        public boolean supports(Event event, Context context) {
            return (event == Event.BEFORE_EACH_MIGRATE
                    || event == Event.AFTER_EACH_MIGRATE)
                    && context.getMigrationInfo() != null
                    && context.getMigrationInfo().getVersion() != null
                    && "4".equals(context.getMigrationInfo().getVersion().getVersion());
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                beforeV4 = readSessionTimeZone(context.getConnection());
            } else if (event == Event.AFTER_EACH_MIGRATE) {
                afterV4 = readSessionTimeZone(context.getConnection());
            }
        }

        @Override
        public String getCallbackName() {
            return "V4 세션 시간대 복원 검증";
        }

        private String beforeV4() {
            return beforeV4;
        }

        private String afterV4() {
            return afterV4;
        }
    }

    private record AbsoluteTimes(
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            Instant requestCreatedAt
    ) {
    }

    private record TimeColumn(
            String tableName,
            String columnName,
            String dataType,
            int datetimePrecision,
            String nullable
    ) {
    }

}
