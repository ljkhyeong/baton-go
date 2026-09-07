package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.in.web.link.LinkResolverController;
import com.personal.batongo.adapter.in.web.link.PublicLinkExceptionHandler;
import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        classes = DistributedResolverQuotaHttpIntegrationTest.WebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "baton-go.public-resolver-rate-limit.capacity=1000",
                "baton-go.public-resolver-rate-limit.window=15s",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"
        }
)
@DirtiesContext
class DistributedResolverQuotaHttpIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(PublicResolverRateLimitProperties.class)
    @Import({
            ManagementApiSecurityConfiguration.class,
            FilterErrorResponseWriter.class,
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
    static class WebConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @MockitoBean private SmartLinkUseCase useCase;
    @MockitoBean private PublicResolverQuotaPort quota;
    @Value("${local.server.port}") private int port;

    @Test
    @DisplayName("분산 제한의 429와 장애 503은 공개 JSON·HTML에 표시하고 링크 조회를 실행하지 않는다")
    void blocksBeforeLinkLookup() throws Exception {
        when(quota.acquireRetryAfterSeconds()).thenReturn(7L);
        var client = HttpClient.newHttpClient();
        var uri = URI.create("http://localhost:" + port + "/l/" + "A".repeat(22));
        var limited = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("7");
        when(quota.acquireRetryAfterSeconds()).thenThrow(new PublicResolverQuotaUnavailableException());
        var json = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.statusCode()).isEqualTo(503);
        assertThat(json.body()).contains("RATE_LIMIT_UNAVAILABLE", "요청 처리 한도를 확인하지 못했습니다");
        var html = client.send(HttpRequest.newBuilder(uri).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(html.statusCode()).isEqualTo(503);
        assertThat(html.body())
                .contains("지금은 링크를 열 수 없습니다.", "잠시 후 다시 열어 주세요")
                .doesNotContain("요청 처리 한도를 확인하지 못했습니다");
        var head = client.send(HttpRequest.newBuilder(uri).header("Accept", "text/html")
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(head.statusCode()).isEqualTo(503);
        assertThat(head.body()).isEmpty();
        verifyNoInteractions(useCase);
    }
}
