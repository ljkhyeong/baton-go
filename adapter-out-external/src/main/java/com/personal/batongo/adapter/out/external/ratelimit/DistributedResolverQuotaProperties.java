package com.personal.batongo.adapter.out.external.ratelimit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("baton-go.distributed-resolver-quota")
@Validated
public record DistributedResolverQuotaProperties(
        @DefaultValue("false") boolean enabled,
        String redisUri,
        @DefaultValue("300")
        @Min(value = 1, message = "분산 요청 제한 용량은 1 이상이어야 합니다")
        @Max(value = 1_000_000_000L, message = "분산 요청 제한 용량은 1,000,000,000 이하여야 합니다")
        long capacity,
        @DefaultValue("1m")
        @NotNull(message = "분산 요청 제한 구간은 필수입니다")
        @DurationMin(millis = 1, message = "분산 요청 제한 구간은 1ms 이상이어야 합니다")
        @DurationMax(days = 1, message = "분산 요청 제한 구간은 1일 이하여야 합니다")
        Duration window,
        @DefaultValue("500ms")
        @NotNull(message = "분산 요청 제한 Redis 대기 시간은 필수입니다")
        @DurationMin(millis = 1, message = "분산 요청 제한 Redis 대기 시간은 1ms 이상이어야 합니다")
        @DurationMax(seconds = 5, message = "분산 요청 제한 Redis 대기 시간은 5초 이하여야 합니다")
        Duration timeout
) {
    public DistributedResolverQuotaProperties {
        if (enabled && (redisUri == null || redisUri.isBlank())) {
            throw new IllegalArgumentException("분산 요청 제한의 Redis 주소가 필요합니다");
        }
    }

    @Override
    public String toString() {
        return "DistributedResolverQuotaProperties[redacted]";
    }
}
