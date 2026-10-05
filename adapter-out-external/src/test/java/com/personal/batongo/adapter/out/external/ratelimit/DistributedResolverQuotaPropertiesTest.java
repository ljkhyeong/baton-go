package com.personal.batongo.adapter.out.external.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DistributedResolverQuotaPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"capacity=0", "capacity=1000000001", "window=0ms", "window=2d"})
    @DisplayName("Redis 분산 제한 설정이 허용 범위를 벗어나면 바인딩 단계에서 시작을 거부한다")
    void rejectsInvalidConfiguration(String property) {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("baton-go.distributed-resolver-quota." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DistributedResolverQuotaProperties.class)
    static class PropertiesConfiguration {
    }
}
