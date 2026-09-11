package com.personal.batongo.bootstrap.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.batongo.application.link.LinkRetentionService;
import com.personal.batongo.application.link.port.out.LinkRetentionPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkRetentionSchedulerTest {

    @Test
    @DisplayName("자동 정리 실행 완료 시각은 성공과 실패 뒤 모두 갱신한다")
    void updatesHeartbeatAfterSuccessfulAndFailedRuns() {
        Clock clock = mock();
        when(clock.instant()).thenReturn(
                Instant.parse("2026-09-12T00:00:00Z"),
                Instant.parse("2026-09-12T00:01:00Z"),
                Instant.parse("2026-09-12T00:01:01Z"),
                Instant.parse("2026-09-12T00:02:00Z"),
                Instant.parse("2026-09-12T00:02:01Z")
        );
        LinkRetentionPort retention = mock();
        when(retention.purgeRetiredLinks(any(), any(), anyInt()))
                .thenReturn(3)
                .thenThrow(new IllegalStateException("test failure"));
        var meters = new SimpleMeterRegistry();
        var scheduler = new LinkRetentionScheduler(
                new LinkRetentionService(retention, clock),
                new LinkRetentionProperties(
                        true,
                        Duration.ofDays(30),
                        100,
                        Duration.ofSeconds(60)
                ),
                meters,
                clock
        );

        scheduler.purge();

        assertThat(meters.get("baton.go.link.retention.purged").counter().count())
                .isEqualTo(3);
        assertThat(meters.get("baton.go.link.retention.scheduler.heartbeat.seconds")
                .gauge().value()).isEqualTo(Instant.parse("2026-09-12T00:01:01Z").getEpochSecond());
        assertThat(meters.get("baton.go.link.retention.scheduler.interval.seconds")
                .gauge().value()).isEqualTo(60);

        scheduler.purge();

        assertThat(meters.get("baton.go.link.retention.failures").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("baton.go.link.retention.scheduler.heartbeat.seconds")
                .gauge().value()).isEqualTo(Instant.parse("2026-09-12T00:02:01Z").getEpochSecond());
    }
}
