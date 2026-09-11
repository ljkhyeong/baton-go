package com.personal.batongo.bootstrap.retention;

import com.personal.batongo.application.link.LinkRetentionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Counter purged;
    private final Counter failures;

    public LinkRetentionScheduler(LinkRetentionService retention, LinkRetentionProperties properties,
                                  MeterRegistry meters) {
        this.retention = retention;
        this.properties = properties;
        purged = meters.counter("baton.go.link.retention.purged");
        failures = meters.counter("baton.go.link.retention.failures");
    }

    @Scheduled(fixedDelayString = "${baton-go.link-retention.interval:60s}",
               initialDelayString = "${baton-go.link-retention.interval:60s}")
    public void purge() {
        try {
            purged.increment(retention.purge(properties.period(), properties.batchSize()));
        } catch (RuntimeException exception) {
            failures.increment();
            LOG.error("종료 링크 자동 정리 실패 exceptionType={}", exception.getClass().getName());
        }
    }
}
