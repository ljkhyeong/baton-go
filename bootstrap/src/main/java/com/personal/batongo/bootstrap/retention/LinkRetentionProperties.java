package com.personal.batongo.bootstrap.retention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("baton-go.link-retention")
@Validated
public record LinkRetentionProperties(
        @DefaultValue("false") boolean enabled,
        @DurationMin(inclusive = false, message = "링크 정리 보존 기간은 양수여야 합니다")
        Duration period,
        @DefaultValue("100")
        @Min(value = 1, message = "한 번에 정리할 링크 수는 1개 이상이어야 합니다")
        @Max(value = 500, message = "한 번에 정리할 링크 수는 500개 이하여야 합니다")
        int batchSize
) {
    public LinkRetentionProperties {
        if (enabled && period == null) {
            throw new IllegalArgumentException("링크 정리를 사용하려면 보존 기간을 명시해야 합니다");
        }
    }
}
