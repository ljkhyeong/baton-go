package com.personal.batongo.bootstrap;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("resolverQuotaRedis")
public class DistributedResolverQuotaHealthIndicator implements HealthIndicator {

    // Redis 연결 빈은 분산 요청 제한을 켰을 때만 만들어진다.
    private final Optional<StatefulRedisConnection<String, String>> connection;

    public DistributedResolverQuotaHealthIndicator(
            Optional<StatefulRedisConnection<String, String>> connection
    ) {
        this.connection = connection;
    }

    @Override
    public Health health() {
        if (connection.isEmpty()) {
            return Health.up().build();
        }
        try {
            connection.get().sync().ping();
            return Health.up().build();
        } catch (RedisException exception) {
            return Health.down().build();
        }
    }
}
