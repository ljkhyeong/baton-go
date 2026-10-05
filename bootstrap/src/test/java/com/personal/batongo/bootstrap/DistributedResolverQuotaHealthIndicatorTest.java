package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class DistributedResolverQuotaHealthIndicatorTest {

    @Test
    @DisplayName("분산 요청 제한을 끄면 Redis 연결 없이 준비 상태를 유지한다")
    void staysUpWithoutRedisWhenQuotaIsDisabled() {
        var indicator = new DistributedResolverQuotaHealthIndicator(Optional.empty());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("분산 요청 제한을 켜면 Redis PING 실패를 준비 상태 장애로 반환한다")
    void reportsRedisFailureWhenQuotaIsEnabled() {
        StatefulRedisConnection<String, String> connection = mock();
        RedisCommands<String, String> commands = mock();
        when(connection.sync()).thenReturn(commands);
        when(commands.ping()).thenReturn("PONG").thenThrow(new RedisException("연결 끊김"));
        var indicator = new DistributedResolverQuotaHealthIndicator(Optional.of(connection));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
