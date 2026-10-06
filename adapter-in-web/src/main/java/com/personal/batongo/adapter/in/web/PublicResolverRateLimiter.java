package com.personal.batongo.adapter.in.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PublicResolverRateLimiter {

    private final Clock clock;
    private final long capacity;
    private final Duration window;

    private Instant windowStartedAt;
    private long permitsUsed;

    public PublicResolverRateLimiter(
            Clock clock,
            PublicResolverRateLimitProperties properties
    ) {
        this.clock = clock;
        this.capacity = properties.capacity();
        this.window = properties.window();
    }

    /** 허용하면 0, 거부하면 다음 구간까지 남은 시간을 올림한 Retry-After 초를 반환한다. */
    synchronized long acquireRetryAfterSeconds() {
        Instant now = clock.instant();
        Duration elapsed = windowStartedAt == null ? window : Duration.between(windowStartedAt, now);
        // 첫 요청, 구간 경과와 시계 역행은 새 구간을 시작한다.
        if (elapsed.isNegative() || elapsed.compareTo(window) >= 0) {
            windowStartedAt = now;
            permitsUsed = 0;
        }
        if (permitsUsed < capacity) {
            permitsUsed++;
            return 0;
        }
        Duration remaining = window.minus(elapsed);
        return remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
    }
}
