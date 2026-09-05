package com.personal.batongo.adapter.out.external.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton-go.distributed-resolver-quota")
public record DistributedResolverQuotaProperties(@DefaultValue("false") boolean enabled, String redisUri,
        @DefaultValue("300") long capacity, @DefaultValue("1m") Duration window,
        @DefaultValue("500ms") Duration timeout) {
    public DistributedResolverQuotaProperties {
        if (enabled && (redisUri == null || redisUri.isBlank())) {
            throw new IllegalArgumentException("분산 요청 제한의 Redis 주소가 필요합니다");
        }
        if (capacity < 1 || capacity > 1_000_000_000L || window.toMillis() < 1
                || window.compareTo(Duration.ofDays(1)) > 0 || timeout.toMillis() < 1
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("분산 요청 제한의 용량·시간 구간·대기 시간이 지원 범위를 벗어났습니다");
        }
    }

    @Override public String toString() { return "DistributedResolverQuotaProperties[redacted]"; }
}
