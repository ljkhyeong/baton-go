package com.personal.batongo.bootstrap;

import com.personal.batongomigration.DatabaseMigrationConfiguration;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Kubernetes의 일회성 database migration Job을 위한 최소 Spring context입니다.
 *
 * <p>일반 애플리케이션 component scan, 웹 서버, JPA와 링크 코드 guard를 시작하지 않고
 * DataSource와 Flyway만 조립합니다. 따라서 Job에는 migration credential만, 장기 실행
 * application container에는 runtime DML credential만 주입할 수 있습니다.</p>
 */
public final class DatabaseMigrationRunner {

    static final String MIGRATION_ONLY_ARGUMENT = "--baton-go.migration-only=true";

    private DatabaseMigrationRunner() {
    }

    public static boolean isRequested(String[] args) {
        return Arrays.asList(args).contains(MIGRATION_ONLY_ARGUMENT);
    }

    public static void run(String[] args) {
        SpringApplication application = new SpringApplication(
                DatabaseMigrationConfiguration.class
        );
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        application.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = application.run(args)) {
            Flyway flyway = context.getBeanProvider(Flyway.class).getIfUnique();
            if (flyway == null) {
                throw new IllegalStateException(
                        "migration-only 실행에 필요한 Flyway bean이 없습니다. "
                                + "spring.flyway.enabled=true와 datasource 설정을 확인하세요"
                );
            }

            migrateToLatest(flyway);
        }
    }

    static void migrateToLatest(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        MigrationVersion configuredTarget = configuration.getTarget();
        if (configuredTarget != null
                && !MigrationVersion.LATEST.equals(configuredTarget)) {
            throw new IllegalStateException(
                    "migration-only 실행은 Flyway latest target만 지원합니다"
            );
        }
        if (configuration.isSkipExecutingMigrations()
                || configuration.isBaselineOnMigrate()
                || !configuration.isValidateOnMigrate()
                || !configuration.isValidateMigrationNaming()
                || hasEntries(configuration.getCherryPick())
                || hasEntries(configuration.getIgnoreMigrationPatterns())) {
            throw new IllegalStateException(
                    "migration-only 실행은 migration을 생략하거나 선택하는 Flyway 설정을 지원하지 않습니다"
            );
        }

        MigrationInfoService configuredMigrations = flyway.info();
        MigrationInfo[] migrations = configuredMigrations.all();
        boolean hasBaselineState = Arrays.stream(migrations)
                .map(MigrationInfo::getState)
                .anyMatch(DatabaseMigrationRunner::isBaselineState);
        if (hasBaselineState) {
            throw new IllegalStateException(
                    "migration-only 실행은 Flyway baseline으로 생략된 schema를 허용하지 않습니다"
            );
        }
        boolean hasResolvedMigration = Arrays.stream(migrations)
                .map(MigrationInfo::getState)
                .anyMatch(state -> state.isResolved());
        if (!hasResolvedMigration) {
            throw new IllegalStateException(
                    "migration-only 실행에서 적용할 Flyway migration을 찾을 수 없습니다"
            );
        }

        flyway.migrate();

        MigrationInfoService info = flyway.info();
        if (info.pending().length > 0) {
            throw new IllegalStateException(
                    "Flyway migration 뒤에도 적용되지 않은 migration이 남아 있습니다"
            );
        }

        flyway.validate();
    }

    private static boolean hasEntries(Object[] values) {
        return values != null && values.length > 0;
    }

    private static boolean isBaselineState(MigrationState state) {
        return state == MigrationState.BASELINE
                || state == MigrationState.BASELINE_IGNORED
                || state == MigrationState.BELOW_BASELINE;
    }
}
