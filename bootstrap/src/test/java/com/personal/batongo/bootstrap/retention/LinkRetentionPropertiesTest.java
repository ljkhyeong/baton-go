package com.personal.batongo.bootstrap.retention;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class LinkRetentionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "baton-go.link-retention.enabled=true",
                    "baton-go.link-retention.period=30d",
                    "baton-go.link-retention.batch-size=100"
            );

    @ParameterizedTest
    @ValueSource(strings = {"period=", "period=0s", "batch-size=0", "batch-size=501"})
    @DisplayName("자동 정리의 보존 기간은 양수이고 실행당 링크 수는 1~500개여야 한다")
    void rejectsInvalidConfiguration(String property) {
        contextRunner.withPropertyValues("baton-go.link-retention." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("자동 정리가 꺼져 있으면 보존 기간 없이 기본 설정을 바인딩한다")
    void allowsMissingPeriodWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LinkRetentionProperties.class)
    static class PropertiesConfiguration {
    }
}
