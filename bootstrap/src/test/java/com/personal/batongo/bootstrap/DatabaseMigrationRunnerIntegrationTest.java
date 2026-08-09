package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.batongo.BatonGoApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
class DatabaseMigrationRunnerIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

    @Test
    @DisplayName("migration-only 실행은 일반 애플리케이션 없이 최신 Flyway 스키마를 적용하고 종료한다")
    void migratesSchemaAndReturns() throws SQLException {
        BatonGoApplication.main(new String[]{
                "--baton-go.migration-only=true",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.flyway.locations=classpath:db/migration",
                "--logging.level.root=OFF"
        });

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success = 1
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("version")).isEqualTo("5");
        }
    }
}
