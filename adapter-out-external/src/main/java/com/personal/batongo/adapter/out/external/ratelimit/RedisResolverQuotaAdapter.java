package com.personal.batongo.adapter.out.external.ratelimit;

import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "baton-go.distributed-resolver-quota.enabled", havingValue = "true")
public class RedisResolverQuotaAdapter implements PublicResolverQuotaPort {
    private static final String KEY = "baton-go:public-resolver:quota:v1";
    private static final String SCRIPT = """
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            local ttl = redis.call('PTTL', KEYS[1])
            if count > 0 and ttl < 0 then return -1 end
            if count >= tonumber(ARGV[1]) then return math.max(1, ttl) end
            redis.call('INCR', KEYS[1])
            if count == 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """;
    private final StatefulRedisConnection<String, String> connection;
    private final DistributedResolverQuotaProperties properties;

    public RedisResolverQuotaAdapter(StatefulRedisConnection<String, String> connection,
                                     DistributedResolverQuotaProperties properties) {
        this.connection = connection;
        this.properties = properties;
    }

    @Override
    public long acquireRetryAfterSeconds() {
        try {
            Long remaining = connection.sync().eval(SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{KEY}, Long.toString(properties.capacity()), Long.toString(properties.window().toMillis()));
            if (remaining == null || remaining < 0) throw new PublicResolverQuotaUnavailableException();
            return remaining == 0 ? 0 : (remaining + 999) / 1000;
        } catch (RedisException exception) {
            throw new PublicResolverQuotaUnavailableException();
        }
    }
}
