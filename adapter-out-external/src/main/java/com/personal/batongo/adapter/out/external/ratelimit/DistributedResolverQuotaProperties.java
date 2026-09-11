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
        if (capacity < 1 || capacity > 1_000_000_000L || window.compareTo(Duration.ofMillis(1)) < 0
                || window.compareTo(Duration.ofDays(1)) > 0 || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "분산 요청 제한 용량은 1~1,000,000,000, 시간 구간은 1ms~1일, Redis 대기 시간은 1ms~5초여야 합니다"
            );
        }
    }

    @Override public String toString() { return "DistributedResolverQuotaProperties[redacted]"; }
}
