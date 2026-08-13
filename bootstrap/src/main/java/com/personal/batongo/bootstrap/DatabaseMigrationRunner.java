package com.personal.batongo.bootstrap;

import com.personal.batongomigration.DatabaseMigrationConfiguration;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
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
        application.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = application.run(args)) {
            // Spring Boot's FlywayMigrationInitializer completes before startup returns.
            context.getBean(Flyway.class);
        }
    }
}
