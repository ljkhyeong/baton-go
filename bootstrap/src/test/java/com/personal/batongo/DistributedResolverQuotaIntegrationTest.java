package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.out.external.ratelimit.DistributedResolverQuotaProperties;
import com.personal.batongo.adapter.out.external.ratelimit.RedisResolverQuotaAdapter;
import com.personal.batongo.adapter.out.external.ratelimit.RedisResolverQuotaConfiguration;
import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import com.personal.batongo.bootstrap.DistributedResolverQuotaHealthIndicator;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DistributedResolverQuotaProperties.class)
    @Import({RedisResolverQuotaConfiguration.class, RedisResolverQuotaAdapter.class})
    static class QuotaConfiguration { }

    @Test
    @DisplayName("분산 제한은 기본 중지하며 활성화하면 Redis 서버를 사용한다")
    void assemblesOnlyWhenEnabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(QuotaConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(PublicResolverQuotaPort.class));
        runner.withPropertyValues("baton-go.distributed-resolver-quota.enabled=true",
                "baton-go.distributed-resolver-quota.redis-uri=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
                .run(context -> {
                    assertThat(context).hasSingleBean(PublicResolverQuotaPort.class);
                    assertThat(context.getBean(PublicResolverQuotaPort.class).acquireRetryAfterSeconds()).isZero();
                });
    }

    @Test
    @DisplayName("두 Redis 연결의 동시 요청은 전체 허용량을 공유하고 창이 끝나면 다시 허용한다")
    void sharesLimitAcrossConnectionsAndRecoversAfterExpiry() throws Exception {
        RedisClient client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (var first = client.connect(); var second = client.connect()) {
            first.sync().flushdb();
            var properties = new DistributedResolverQuotaProperties(true, "redis://localhost", 10,
                    Duration.ofSeconds(30), Duration.ofMillis(500));
            var one = new RedisResolverQuotaAdapter(first, properties);
            var two = new RedisResolverQuotaAdapter(second, properties);
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
            String key = first.sync().keys("*").getFirst();
            assertThat(first.sync().get(key)).isEqualTo("10");
            assertThat(first.sync().pttl(key)).isBetween(1L, 30000L);
            first.sync().pexpire(key, 1);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(one.acquireRetryAfterSeconds()).isZero());
            first.sync().persist(key);
            assertThatThrownBy(one::acquireRetryAfterSeconds).isInstanceOf(PublicResolverQuotaUnavailableException.class);
            first.sync().flushdb();
            second.close();
            assertThatThrownBy(two::acquireRetryAfterSeconds).isInstanceOf(PublicResolverQuotaUnavailableException.class);
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("분산 제한 Redis 연결이 끊기면 준비 상태를 장애로 반환한다")
    void reportsClosedRedisConnectionAsUnhealthy() {
        RedisClient client = RedisClient.create(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)
        );
        try (var connection = client.connect()) {
            var properties = new DistributedResolverQuotaProperties(true, "redis://localhost", 10,
                    Duration.ofSeconds(30), Duration.ofMillis(500));
            var health = new DistributedResolverQuotaHealthIndicator(
                    properties, Optional.of(connection)
            );

            assertThat(health.health().getStatus()).isEqualTo(Status.UP);
            connection.close();
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        } finally {
            client.shutdown();
        }
    }
}
