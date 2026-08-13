package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatabaseMigrationConfigurationTest {

    @Test
    @DisplayName("migration-only 실행은 Flyway가 비활성화되면 성공으로 종료하지 않는다")
    void rejectsDisabledFlyway() {
        assertThatThrownBy(() -> DatabaseMigrationRunner.run(new String[]{
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=jdbc:mysql://localhost:3307/baton_go",
                "--spring.datasource.username=baton_go_migrator",
                "--spring.datasource.password=migration-password"
        }))
                .isInstanceOf(RuntimeException.class);
    }
}
