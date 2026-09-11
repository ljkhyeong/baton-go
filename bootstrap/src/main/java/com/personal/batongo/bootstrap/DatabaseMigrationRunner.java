package com.personal.batongo.bootstrap;

import com.personal.batongomigration.DatabaseMigrationConfiguration;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Kubernetes의 일회성 DB 마이그레이션 Job에 필요한 Spring 구성만 시작합니다.
 *
 * <p>일반 애플리케이션 컴포넌트, 웹 서버, JPA와 링크 코드 키 검사는 시작하지 않습니다.
 * Job에는 마이그레이션 계정만, 장기 실행 애플리케이션에는 DML 계정만 주입합니다.</p>
 */
public final class DatabaseMigrationRunner {

    private static final String MIGRATION_ONLY_ARGUMENT = "--baton-go.migration-only=true";

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
        application.setAdditionalProfiles("migration");

        try (ConfigurableApplicationContext context = application.run(args)) {
            // Spring Boot가 이 시점 전에 Flyway 마이그레이션을 완료한다.
            context.getBean(Flyway.class);
        }
    }
}
