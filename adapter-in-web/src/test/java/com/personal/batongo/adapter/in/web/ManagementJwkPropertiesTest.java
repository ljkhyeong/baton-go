package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ManagementJwkPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout", "read-timeout"})
    @DisplayName("관리 JWK 대기 시간 제한을 해제하는 0 설정은 시작 단계에서 거부한다")
    void rejectsUnlimitedTimeout(String property) {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("baton-go.management-jwk." + property + "=0ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ManagementJwkProperties.class)
    static class PropertiesConfiguration {
    }
}
