package com.personal.batongo.bootstrap.retention;

import com.personal.batongo.application.link.LinkRetentionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "baton-go.link-retention.enabled", havingValue = "true")
public class LinkRetentionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LinkRetentionScheduler.class);
    private final LinkRetentionService retention;
    private final LinkRetentionProperties properties;
    private final Clock clock;
    private final AtomicLong heartbeatEpochSeconds;
    private final Counter purged;
    private final Counter failures;

    public LinkRetentionScheduler(LinkRetentionService retention, LinkRetentionProperties properties,
                                  MeterRegistry meters, Clock clock) {
        this.retention = retention;
        this.properties = properties;
        this.clock = clock;
        this.heartbeatEpochSeconds = new AtomicLong(clock.instant().getEpochSecond());
        purged = meters.counter("baton.go.link.retention.purged");
        failures = meters.counter("baton.go.link.retention.failures");
        Gauge.builder(
                        "baton.go.link.retention.scheduler.heartbeat.seconds",
                        heartbeatEpochSeconds,
                        AtomicLong::doubleValue
                )
                .description("마지막 자동 정리 실행 완료 시각")
                .register(meters);
        Gauge.builder(
                        "baton.go.link.retention.scheduler.interval.seconds",
                        properties,
                        value -> value.interval().getSeconds()
                                + value.interval().getNano() / 1_000_000_000.0
                )
                .description("자동 정리 실행 간격")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${baton-go.link-retention.interval:60s}",
               initialDelayString = "${baton-go.link-retention.interval:60s}")
    public void purge() {
        try {
            purged.increment(retention.purge(properties.period(), properties.batchSize()));
        } catch (RuntimeException exception) {
            failures.increment();
            LOG.error("종료 링크 자동 정리 실패 exceptionType={}", exception.getClass().getName());
        } finally {
            heartbeatEpochSeconds.set(clock.instant().getEpochSecond());
        }
    }
}
