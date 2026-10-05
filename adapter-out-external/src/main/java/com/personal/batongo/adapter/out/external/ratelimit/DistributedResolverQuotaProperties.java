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

/** Redis 연결 주소와 대기 시간은 Spring Boot의 {@code spring.data.redis.*} 설정을 사용한다. */
@ConfigurationProperties("baton-go.distributed-resolver-quota")
@Validated
public record DistributedResolverQuotaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("300")
        @Min(value = 1, message = "분산 요청 제한 용량은 1 이상이어야 합니다")
        @Max(value = 1_000_000_000L, message = "분산 요청 제한 용량은 1,000,000,000 이하여야 합니다")
        long capacity,
        @DefaultValue("1m")
        @NotNull(message = "분산 요청 제한 구간은 필수입니다")
        @DurationMin(millis = 1, message = "분산 요청 제한 구간은 1ms 이상이어야 합니다")
        @DurationMax(days = 1, message = "분산 요청 제한 구간은 1일 이하여야 합니다")
        Duration window
) {
}
