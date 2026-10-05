package com.personal.batongo.adapter.out.external.ratelimit;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Spring Boot가 만든 Redis 연결로 분산 요청 제한을 켠다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "baton-go.distributed-resolver-quota.enabled", havingValue = "true")
public class RedisResolverQuotaConfiguration {

    @Bean
    LettuceClientOptionsBuilderCustomizer resolverQuotaClientOptions() {
        // 연결이 끊긴 동안 명령을 쌓아 두지 않고 바로 실패시켜 공개 요청에 503을 반환한다.
        return options -> options
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(256);
    }

    @Bean
    RedisResolverQuotaAdapter redisResolverQuotaAdapter(
            StringRedisTemplate redis, DistributedResolverQuotaProperties properties
    ) {
        return new RedisResolverQuotaAdapter(redis, properties);
    }
}
