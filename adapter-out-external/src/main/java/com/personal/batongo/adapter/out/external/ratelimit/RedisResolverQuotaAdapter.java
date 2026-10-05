package com.personal.batongo.adapter.out.external.ratelimit;

import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public class RedisResolverQuotaAdapter implements PublicResolverQuotaPort {
    private static final List<String> KEYS = List.of("baton-go:public-resolver:quota:v1");
    private static final RedisScript<Long> SCRIPT = RedisScript.of("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            local ttl = redis.call('PTTL', KEYS[1])
            if count > 0 and ttl < 0 then return -1 end
            if count >= tonumber(ARGV[1]) then return math.max(1, ttl) end
            redis.call('INCR', KEYS[1])
            if count == 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final DistributedResolverQuotaProperties properties;

    public RedisResolverQuotaAdapter(StringRedisTemplate redis, DistributedResolverQuotaProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public long acquireRetryAfterSeconds() {
        Long remaining;
        try {
            remaining = redis.execute(SCRIPT, KEYS,
                    Long.toString(properties.capacity()), Long.toString(properties.window().toMillis()));
        } catch (DataAccessException exception) {
            throw new PublicResolverQuotaUnavailableException();
        }
        if (remaining == null || remaining < 0) throw new PublicResolverQuotaUnavailableException();
        return Math.ceilDiv(remaining, 1000);
    }
}
