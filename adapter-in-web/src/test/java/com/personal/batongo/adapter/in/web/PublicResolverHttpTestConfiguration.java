package com.personal.batongo.adapter.in.web;

import com.personal.batongo.adapter.in.web.link.LinkResolverController;
import com.personal.batongo.adapter.in.web.link.PublicLinkExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 공개 링크 조회와 요청 제한을 실제 HTTP 서버로 확인하는 테스트의 공통 구성입니다. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableConfigurationProperties(PublicResolverRateLimitProperties.class)
@Import({
        ManagementApiSecurityConfiguration.class,
        LinkResolverController.class,
        GlobalExceptionHandler.class,
        PublicLinkExceptionHandler.class,
        RequestIdFilter.class,
        WebMvcConfiguration.class,
        PublicResolverWebMvcConfiguration.class,
        PublicResolverRateLimitInterceptor.class,
        PublicResolverRateLimiter.class,
        PublicLinkErrorPage.class,
        SimpleMeterRegistry.class
})
class PublicResolverHttpTestConfiguration {

    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
