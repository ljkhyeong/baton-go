package com.personal.batongo.adapter.out.external.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "baton-go.distributed-resolver-quota.enabled", havingValue = "true")
public class RedisResolverQuotaConfiguration {
    @Bean(destroyMethod = "shutdown")
    RedisClient resolverRedisClient(DistributedResolverQuotaProperties properties) {
        try {
            RedisURI uri = RedisURI.create(properties.redisUri());
            uri.setTimeout(properties.timeout());
            RedisClient client = RedisClient.create(uri);
            client.setOptions(ClientOptions.builder()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .requestQueueSize(256)
                    .socketOptions(SocketOptions.builder().connectTimeout(properties.timeout()).build())
                    .timeoutOptions(TimeoutOptions.enabled(properties.timeout()))
                    .build());
            return client;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("분산 요청 제한 Redis 주소와 대기 시간 설정을 확인하세요");
        }
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> resolverRedisConnection(RedisClient resolverRedisClient) {
        try {
            return resolverRedisClient.connect();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("분산 요청 제한용 Redis에 연결하지 못했습니다");
        }
    }
}
