package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.out.external.ratelimit.DistributedResolverQuotaProperties;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class DistributedResolverQuotaHealthIndicatorTest {

    @Test
    @DisplayName("분산 요청 제한을 끄면 Redis 연결 없이 준비 상태를 유지한다")
    void staysUpWithoutRedisWhenQuotaIsDisabled() {
        var indicator = new DistributedResolverQuotaHealthIndicator(
                properties(false), Optional.empty()
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("분산 요청 제한을 켜면 Redis PING 실패를 준비 상태 장애로 반환한다")
    void reportsRedisFailureWhenQuotaIsEnabled() {
        StatefulRedisConnection<String, String> connection = mock();
        RedisCommands<String, String> commands = mock();
        when(connection.sync()).thenReturn(commands);
        when(commands.ping()).thenReturn("PONG").thenThrow(new RedisException("연결 끊김"));
        var indicator = new DistributedResolverQuotaHealthIndicator(
                properties(true), Optional.of(connection)
        );

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private DistributedResolverQuotaProperties properties(boolean enabled) {
        return new DistributedResolverQuotaProperties(
                enabled,
                enabled ? "redis://localhost:6379" : null,
                300,
                Duration.ofMinutes(1),
                Duration.ofMillis(500)
        );
    }
}
