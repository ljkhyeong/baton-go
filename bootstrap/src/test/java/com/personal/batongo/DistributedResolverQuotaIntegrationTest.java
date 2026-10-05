package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.out.external.ratelimit.DistributedResolverQuotaProperties;
import com.personal.batongo.adapter.out.external.ratelimit.RedisResolverQuotaAdapter;
import com.personal.batongo.adapter.out.external.ratelimit.RedisResolverQuotaConfiguration;
import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("redis")
@Testcontainers
class DistributedResolverQuotaIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:7-alpine@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99")
            .withExposedPorts(6379);

    // 운영 application.yml의 Redis 연결·대기 시간·상태 확인 설정을 그대로 읽는다.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                    DataRedisHealthContributorAutoConfiguration.class,
                    DataRedisReactiveHealthContributorAutoConfiguration.class))
            .withUserConfiguration(QuotaConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DistributedResolverQuotaProperties.class)
    @Import(RedisResolverQuotaConfiguration.class)
    static class QuotaConfiguration { }

    @Test
    @DisplayName("분산 제한은 기본 중지하며 활성화하면 Redis 요청 제한과 준비 상태 확인을 함께 켠다")
    void assemblesOnlyWhenEnabled() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(PublicResolverQuotaPort.class)
                .doesNotHaveBean("redisHealthContributor"));
        runner.withPropertyValues("baton-go.distributed-resolver-quota.enabled=true", "spring.data.redis.url=" + redisUrl())
                .run(context -> {
                    assertThat(context.getBean(PublicResolverQuotaPort.class).acquireRetryAfterSeconds()).isZero();
                    assertThat(redisHealth(context)).isEqualTo(Status.UP);
                });
    }

    @Test
    @DisplayName("분산 제한 Redis에 연결하지 못하면 공개 요청 제한과 준비 상태가 대기 시간 안에 장애로 응답한다")
    void reportsUnreachableRedisAsUnavailable() {
        runner.withPropertyValues("baton-go.distributed-resolver-quota.enabled=true",
                        "spring.data.redis.url=redis://127.0.0.1:1")
                .run(context -> {
                    assertThatThrownBy(context.getBean(PublicResolverQuotaPort.class)::acquireRetryAfterSeconds)
                            .isInstanceOf(PublicResolverQuotaUnavailableException.class);
                    assertThat(redisHealth(context)).isEqualTo(Status.DOWN);
                });
    }

    @Test
    @DisplayName("두 Redis 연결의 동시 요청은 전체 허용량을 공유하고 창이 끝나면 다시 허용한다")
    void sharesLimitAcrossConnectionsAndRecoversAfterExpiry() throws Exception {
        var properties = new DistributedResolverQuotaProperties(true, 10, Duration.ofSeconds(30));
        LettuceConnectionFactory firstConnection = connectionFactory();
        LettuceConnectionFactory secondConnection = connectionFactory();
        try {
            var redis = new StringRedisTemplate(firstConnection);
            redis.delete(redis.keys("*"));
            var one = new RedisResolverQuotaAdapter(redis, properties);
            var two = new RedisResolverQuotaAdapter(new StringRedisTemplate(secondConnection), properties);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var requests = new ArrayList<Future<Long>>();
                for (int i = 0; i < 50; i++) {
                    var adapter = i % 2 == 0 ? one : two;
                    requests.add(executor.submit(adapter::acquireRetryAfterSeconds));
                }
                int allowed = 0;
                for (Future<Long> request : requests) {
                    long retryAfter = request.get();
                    if (retryAfter == 0) allowed++;
                    else assertThat(retryAfter).isBetween(1L, 30L);
                }
                assertThat(allowed).isEqualTo(10);
            }
            String key = redis.keys("*").iterator().next();
            assertThat(redis.opsForValue().get(key)).isEqualTo("10");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 30000L);
            redis.expire(key, Duration.ofMillis(1));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(one.acquireRetryAfterSeconds()).isZero());
            redis.persist(key);
            assertThatThrownBy(one::acquireRetryAfterSeconds).isInstanceOf(PublicResolverQuotaUnavailableException.class);
        } finally {
            firstConnection.destroy();
            secondConnection.destroy();
        }
    }

    private static String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    private static LettuceConnectionFactory connectionFactory() {
        var factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static Status redisHealth(ApplicationContext context) {
        Object contributor = context.getBean("redisHealthContributor");
        return contributor instanceof ReactiveHealthIndicator reactive
                ? reactive.health().block(Duration.ofSeconds(5)).getStatus()
                : ((HealthIndicator) contributor).health().getStatus();
    }
}
