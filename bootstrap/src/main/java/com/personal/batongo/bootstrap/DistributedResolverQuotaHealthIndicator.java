package com.personal.batongo.bootstrap;

import com.personal.batongo.adapter.out.external.ratelimit.DistributedResolverQuotaProperties;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("resolverQuotaRedis")
public class DistributedResolverQuotaHealthIndicator implements HealthIndicator {

    private final DistributedResolverQuotaProperties properties;
    private final Optional<StatefulRedisConnection<String, String>> connection;

    public DistributedResolverQuotaHealthIndicator(
            DistributedResolverQuotaProperties properties,
            Optional<StatefulRedisConnection<String, String>> connection
    ) {
        this.properties = properties;
        this.connection = connection;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up().build();
        }
        if (connection.isEmpty()) {
            return Health.down().build();
        }
        try {
            connection.get().sync().ping();
            return Health.up().build();
        } catch (RedisException exception) {
            return Health.down().build();
        }
    }
}
