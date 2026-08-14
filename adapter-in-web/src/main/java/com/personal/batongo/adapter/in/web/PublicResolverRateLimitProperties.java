package com.personal.batongo.adapter.in.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.public-resolver-rate-limit")
public record PublicResolverRateLimitProperties(
        long capacity,
        Duration window
) {

    public PublicResolverRateLimitProperties {
        if (capacity <= 0) {
            throw new IllegalArgumentException("공개 resolver rate limit capacity는 양수여야 합니다");
        }
        if (window == null) {
            throw new IllegalArgumentException("공개 resolver rate limit window가 필요합니다");
        }
        if (!window.isPositive()) {
            throw new IllegalArgumentException("공개 resolver rate limit window는 양수여야 합니다");
        }
    }
}
