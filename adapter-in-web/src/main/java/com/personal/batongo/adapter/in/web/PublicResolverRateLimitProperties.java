package com.personal.batongo.adapter.in.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("baton-go.public-resolver-rate-limit")
@Validated
public record PublicResolverRateLimitProperties(
        @Min(value = 1, message = "공개 링크 요청 제한 용량은 1 이상이어야 합니다")
        long capacity,
        @NotNull(message = "공개 링크 요청 제한 구간은 필수입니다")
        @DurationMin(inclusive = false, message = "공개 링크 요청 제한 구간은 양수여야 합니다")
        Duration window
) {
}
