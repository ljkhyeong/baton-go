package com.personal.batongo.adapter.out.external.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DistributedResolverQuotaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "baton-go.distributed-resolver-quota.enabled=true",
                    "baton-go.distributed-resolver-quota.redis-uri=redis://localhost:6379",
                    "baton-go.distributed-resolver-quota.capacity=300",
                    "baton-go.distributed-resolver-quota.window=1m",
                    "baton-go.distributed-resolver-quota.timeout=500ms"
            );

    @ParameterizedTest
    @ValueSource(strings = {
            "redis-uri= ",
            "capacity=0",
            "capacity=1000000001",
            "window=0ms",
            "window=2d",
            "timeout=0ms",
            "timeout=6s"
    })
    @DisplayName("Redis 분산 제한 설정이 허용 범위를 벗어나면 바인딩 단계에서 시작을 거부한다")
    void rejectsInvalidConfiguration(String property) {
        contextRunner.withPropertyValues("baton-go.distributed-resolver-quota." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Redis 분산 제한이 꺼져 있으면 주소 없이 기본 설정을 바인딩한다")
    void allowsMissingRedisUriWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DistributedResolverQuotaProperties.class)
    static class PropertiesConfiguration {
    }
}
