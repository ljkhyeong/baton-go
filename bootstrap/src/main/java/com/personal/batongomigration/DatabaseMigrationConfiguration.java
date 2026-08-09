package com.personal.batongomigration;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 일반 {@code com.personal.batongo} component scan 바깥에 둔 migration-only 구성입니다.
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
public class DatabaseMigrationConfiguration {

    /**
     * Spring context 시작 성공과 migration 성공을 분리합니다.
     *
     * <p>자동 initializer는 Flyway bean 조립까지만 확인하고, 실제 migration과 결과 검증은
     * {@code DatabaseMigrationRunner}가 명시적으로 수행합니다.</p>
     */
    @Bean
    FlywayMigrationStrategy migrationOnlyFlywayMigrationStrategy() {
        return flyway -> {
            // DatabaseMigrationRunner가 context 시작 뒤 명시적으로 migrate하고 검증합니다.
        };
    }
}
