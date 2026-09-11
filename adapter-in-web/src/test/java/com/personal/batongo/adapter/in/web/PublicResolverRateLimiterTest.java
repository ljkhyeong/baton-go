package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.in.web.PublicResolverRateLimiter.RateLimitDecision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicResolverRateLimiterTest {

    private static final Instant START = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    @DisplayName("설정한 용량까지 허용하고 초과 요청에는 남은 시간 구간을 초 단위로 안내한다")
    void rejectsAfterCapacityWithRoundedRetryAfter() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(
                START,
                START,
                START.plusMillis(1_500)
        );
        PublicResolverRateLimiter limiter = limiter(clock, 2, Duration.ofSeconds(10));

        assertThat(limiter.acquire().allowed()).isTrue();
        assertThat(limiter.acquire().allowed()).isTrue();

        RateLimitDecision rejected = limiter.acquire();

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(9);
    }

    @Test
    @DisplayName("새 시간 구간이 시작되면 용량을 다시 부여한다")
    void resetsCapacityAtWindowBoundary() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(
                START,
                START,
                START.plusSeconds(10)
        );
        PublicResolverRateLimiter limiter = limiter(clock, 1, Duration.ofSeconds(10));

        assertThat(limiter.acquire().allowed()).isTrue();
        assertThat(limiter.acquire().allowed()).isFalse();

        assertThat(limiter.acquire().allowed()).isTrue();
    }

    @Test
    @DisplayName("시스템 시계가 뒤로 이동해도 이전 시간 구간에 머물지 않는다")
    void resetsWindowWhenClockMovesBackward() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START, START.minusSeconds(1));
        PublicResolverRateLimiter limiter = limiter(clock, 1, Duration.ofSeconds(10));

        assertThat(limiter.acquire().allowed()).isTrue();
        assertThat(limiter.acquire().allowed()).isTrue();
    }

    @Test
    @DisplayName("동시 요청에서도 설정한 용량만 정확히 허용한다")
    void enforcesCapacityAcrossConcurrentRequests() throws Exception {
        int capacity = 17;
        PublicResolverRateLimiter limiter = limiter(
                Clock.fixed(START, ZoneOffset.UTC),
                capacity,
                Duration.ofMinutes(1)
        );
        ExecutorService executor = Executors.newFixedThreadPool(16);

        try {
            List<Callable<Boolean>> calls = IntStream.range(0, 200)
                    .mapToObj(index -> (Callable<Boolean>) () -> limiter.acquire().allowed())
                    .toList();
            List<Future<Boolean>> results = executor.invokeAll(calls);

            long allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(capacity);
        } finally {
            executor.shutdownNow();
        }
    }

    private PublicResolverRateLimiter limiter(
            Clock clock,
            long capacity,
            Duration window
    ) {
        return new PublicResolverRateLimiter(
                clock,
                new PublicResolverRateLimitProperties(capacity, window)
        );
    }

}
