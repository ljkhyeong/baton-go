package com.personal.batongomigration;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
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
}
