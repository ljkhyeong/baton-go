package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

class DatabaseMigrationConfigurationTest {

    @Test
    @DisplayName("마이그레이션 전용 실행은 Flyway가 비활성화되면 성공으로 종료하지 않는다")
    void rejectsDisabledFlyway() {
        assertThatThrownBy(() -> DatabaseMigrationRunner.run(new String[]{
                "--spring.flyway.enabled=false",
                "--spring.datasource.url=jdbc:mysql://localhost:3307/baton_go",
                "--spring.datasource.username=baton_go_migrator",
                "--spring.datasource.password=migration-password"
        }))
                .isInstanceOfSatisfying(
                        NoSuchBeanDefinitionException.class,
                        exception -> assertThat(exception.getBeanType()).isEqualTo(Flyway.class)
                );
    }
}
