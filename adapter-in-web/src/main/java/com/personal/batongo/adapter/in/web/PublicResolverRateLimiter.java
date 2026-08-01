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

    public synchronized RateLimitDecision acquire() {
        Instant now = clock.instant();
        if (windowStartedAt == null) {
            return startWindow(now);
        }

        Duration elapsed = Duration.between(windowStartedAt, now);
        if (elapsed.isNegative() || elapsed.compareTo(window) >= 0) {
            return startWindow(now);
        }
        if (permitsUsed < capacity) {
            permitsUsed++;
            return RateLimitDecision.permitted();
        }

        return RateLimitDecision.rejected(retryAfterSeconds(window.minus(elapsed)));
    }

    private RateLimitDecision startWindow(Instant now) {
        windowStartedAt = now;
        permitsUsed = 1;
        return RateLimitDecision.permitted();
    }

    private long retryAfterSeconds(Duration remaining) {
        long seconds = remaining.getSeconds();
        if (remaining.getNano() > 0) {
            seconds++;
        }
        return Math.max(1, seconds);
    }

    public record RateLimitDecision(
            boolean allowed,
            long retryAfterSeconds
    ) {

        private static RateLimitDecision permitted() {
            return new RateLimitDecision(true, 0);
        }

        private static RateLimitDecision rejected(long retryAfterSeconds) {
            return new RateLimitDecision(false, retryAfterSeconds);
        }
    }
}
