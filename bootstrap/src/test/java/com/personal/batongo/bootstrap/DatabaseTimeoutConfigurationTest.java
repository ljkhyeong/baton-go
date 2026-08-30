package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabaseTimeoutConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));

    @ParameterizedTest
    @CsvSource({"default, 5000", "migration, 600000"})
    @DisplayName("DB 연결 대기는 제한하고 마이그레이션의 응답 대기 시간은 별도로 적용한다")
    void bindsDatabaseTimeoutsForEachExecutionMode(String profile, String socketTimeout) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);

                    assertThat(dataSource.getConnectionTimeout()).isEqualTo(3000);
                    assertThat(dataSource.getValidationTimeout()).isEqualTo(1000);
                    assertThat(dataSource.getDataSourceProperties())
                            .containsEntry("connectTimeout", "3000")
                            .containsEntry("socketTimeout", socketTimeout);
                });
    }
}
