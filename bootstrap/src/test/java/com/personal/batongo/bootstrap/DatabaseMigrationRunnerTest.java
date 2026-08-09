package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.batongo.BatonGoApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatabaseMigrationRunnerTest {

    @Test
    @DisplayName("migration-only 실행은 Flyway가 비활성화되면 성공으로 오인하지 않고 실패한다")
    void failsClosedWhenFlywayIsDisabled() {
        assertThatThrownBy(() -> BatonGoApplication.main(new String[]{
                "--baton-go.migration-only=true",
                "--spring.flyway.enabled=false",
                "--logging.level.root=OFF"
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration-only 실행에 필요한 Flyway bean이 없습니다");
    }

    @Test
    @DisplayName("migration-only 실행은 일부 migration만 적용하는 Flyway target을 미리 거부한다")
    void rejectsNonLatestMigrationTargetBeforeMigration() {
        assertThatThrownBy(() -> BatonGoApplication.main(new String[]{
                "--baton-go.migration-only=true",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/baton_go",
                "--spring.datasource.username=baton_go_migrator",
                "--spring.datasource.password=migration-password",
                "--spring.flyway.enabled=true",
                "--spring.flyway.target=next",
                "--logging.level.root=OFF"
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Flyway latest target만 지원합니다");
    }

    @Test
    @DisplayName("migration-only 실행은 SQL을 생략하는 Flyway 설정을 미리 거부한다")
    void rejectsSkippedMigrationExecutionBeforeMigration() {
        assertThatThrownBy(() -> BatonGoApplication.main(new String[]{
                "--baton-go.migration-only=true",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/baton_go",
                "--spring.datasource.username=baton_go_migrator",
                "--spring.datasource.password=migration-password",
                "--spring.flyway.enabled=true",
                "--spring.flyway.skip-executing-migrations=true",
                "--logging.level.root=OFF"
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration을 생략하거나 선택하는 Flyway 설정");
    }

    @Test
    @DisplayName("migration-only 실행은 이름이 잘못된 migration을 무시하는 설정을 거부한다")
    void rejectsDisabledMigrationNamingValidation() {
        assertThatThrownBy(() -> BatonGoApplication.main(new String[]{
                "--baton-go.migration-only=true",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/baton_go",
                "--spring.datasource.username=baton_go_migrator",
                "--spring.datasource.password=migration-password",
                "--spring.flyway.enabled=true",
                "--spring.flyway.validate-migration-naming=false",
                "--logging.level.root=OFF"
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration을 생략하거나 선택하는 Flyway 설정");
    }

    @Test
    @DisplayName("migration-only 실행은 migration 전 validation을 끄는 설정을 거부한다")
    void rejectsDisabledValidationBeforeMigration() {
        assertThatThrownBy(() -> BatonGoApplication.main(new String[]{
                "--baton-go.migration-only=true",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/baton_go",
                "--spring.datasource.username=baton_go_migrator",
                "--spring.datasource.password=migration-password",
                "--spring.flyway.enabled=true",
                "--spring.flyway.validate-on-migrate=false",
                "--logging.level.root=OFF"
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migration을 생략하거나 선택하는 Flyway 설정");
    }

    @Test
    @DisplayName("migration-only 실행은 수동 baseline으로 생략된 schema를 변경 전에 거부한다")
    void rejectsExistingBaselineBeforeMigration() {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        MigrationInfoService migrationInfoService = mock(MigrationInfoService.class);
        MigrationInfo baseline = mock(MigrationInfo.class);

        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getTarget()).thenReturn(MigrationVersion.LATEST);
        when(configuration.isValidateOnMigrate()).thenReturn(true);
        when(configuration.isValidateMigrationNaming()).thenReturn(true);
        when(flyway.info()).thenReturn(migrationInfoService);
        when(migrationInfoService.all()).thenReturn(new MigrationInfo[]{baseline});
        when(baseline.getState()).thenReturn(MigrationState.BASELINE);

        assertThatThrownBy(() -> DatabaseMigrationRunner.migrateToLatest(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseline으로 생략된 schema");
        verify(flyway, never()).migrate();
    }
}
