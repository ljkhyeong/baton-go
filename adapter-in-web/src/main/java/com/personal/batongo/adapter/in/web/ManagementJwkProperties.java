package com.personal.batongo.adapter.in.web;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("baton-go.management-jwk")
@Validated
public record ManagementJwkProperties(
        @DefaultValue("3s")
        @DurationMin(millis = 1, message = "관리 JWK 연결 대기 시간은 1ms 이상이어야 합니다")
        Duration connectTimeout,
        @DefaultValue("5s")
        @DurationMin(millis = 1, message = "관리 JWK 읽기 대기 시간은 1ms 이상이어야 합니다")
        Duration readTimeout
) {
}
