package com.personal.batongo.bootstrap.retention;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton-go.link-retention")
public record LinkRetentionProperties(@DefaultValue("false") boolean enabled, Duration period,
                                      @DefaultValue("100") int batchSize) {
    public LinkRetentionProperties {
        if ((enabled && period == null) || (period != null && !period.isPositive())) {
            throw new IllegalArgumentException("링크 정리를 사용하려면 양수인 보존 기간을 명시해야 합니다");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("링크 정리 처리량은 1에서 500 사이여야 합니다");
        }
    }
}
