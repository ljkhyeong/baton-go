package com.personal.batongomigration;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * 일반 {@code com.personal.batongo} 컴포넌트 검색에서 제외한 DB 마이그레이션 전용 구성입니다.
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
public class DatabaseMigrationConfiguration {
}
