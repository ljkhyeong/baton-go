package com.personal.batongo.adapter.in.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PublicResolverRateLimiter {

    private static final RateLimitDecision PERMITTED = new RateLimitDecision(true, 0);

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

    synchronized RateLimitDecision acquire() {
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
            return PERMITTED;
        }

        return new RateLimitDecision(false, retryAfterSeconds(window.minus(elapsed)));
    }

    private RateLimitDecision startWindow(Instant now) {
        windowStartedAt = now;
        permitsUsed = 1;
        return PERMITTED;
    }

    private long retryAfterSeconds(Duration remaining) {
        long seconds = remaining.getSeconds();
        if (remaining.getNano() > 0) {
            seconds++;
        }
        return Math.max(1, seconds);
    }

    record RateLimitDecision(
            boolean allowed,
            long retryAfterSeconds
    ) {
    }
}
