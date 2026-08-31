package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PublicResolverRateLimitPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "baton-go.public-resolver-rate-limit.capacity=300",
                    "baton-go.public-resolver-rate-limit.window=1m"
            );

    @ParameterizedTest
    @ValueSource(strings = {"capacity=0", "window=", "window=0s"})
    @DisplayName("용량이나 구간이 유효하지 않으면 요청 제한 설정 바인딩 단계에서 시작을 거부한다")
    void rejectsInvalidConfiguration(String property) {
        contextRunner.withPropertyValues("baton-go.public-resolver-rate-limit." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("양수인 요청 제한 구간은 나노초 정밀도를 바꾸지 않고 바인딩한다")
    void bindsPositiveWindowWithoutLosingPrecision() {
        contextRunner.withPropertyValues("baton-go.public-resolver-rate-limit.window=1ns")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PublicResolverRateLimitProperties properties =
                            context.getBean(PublicResolverRateLimitProperties.class);
                    assertThat(properties.capacity()).isEqualTo(300);
                    assertThat(properties.window()).isEqualTo(Duration.ofNanos(1));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PublicResolverRateLimitProperties.class)
    static class PropertiesConfiguration {
    }
}
