package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.in.web.link.LinkResolverController;
import com.personal.batongo.adapter.in.web.link.PublicLinkExceptionHandler;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = PublicResolverRateLimitHttpIntegrationTest.WebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "baton-go.public-resolver-rate-limit.capacity=1",
                "baton-go.public-resolver-rate-limit.window=15s",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"
        }
)
@DirtiesContext
class PublicResolverRateLimitHttpIntegrationTest {

    private static final String FIRST_CODE = "first-link-code";
    private static final String LIMITED_CODE = "VOvLShvx93kQpj8x7w2HYQ";
    private static final String REQUEST_ID = "public-rate-limit-integration";

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

    @MockitoBean
    private SmartLinkUseCase useCase;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("실제 HTTP 서버는 요청 제한 응답 형식을 선택하고 HEAD 본문을 보내지 않는다")
    void rendersRateLimitResponsesThroughSpringMvc() throws Exception {
        when(useCase.resolveLink(FIRST_CODE)).thenThrow(new LinkNotFoundException());

        HttpResponse<String> first = request(FIRST_CODE, MediaType.APPLICATION_JSON_VALUE, "GET");

        assertThat(first.statusCode()).isEqualTo(404);

        HttpResponse<String> html = request(
                LIMITED_CODE,
                "application/json;q=0.3,text/html;q=0.9",
                "GET"
        );
        assertCommonRateLimitHeaders(
                html,
                MediaType.TEXT_HTML_VALUE,
                "application/json;q=0.3,text/html;q=0.9"
        );
        assertThat(html.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("default-src 'none'");
        assertThat(html.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(html.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
        assertThat(html.body()).contains(
                        "<html lang=\"ko\">",
                        "15초 후에 다시 열어 주세요",
                        "<a class=\"retry\" href=\"\">다시 열기</a>",
                        "<code>" + REQUEST_ID + "</code>"
                )
                .doesNotContain(LIMITED_CODE, "<script", "http-equiv=\"refresh\"");

        for (String accept : new String[]{
                MediaType.APPLICATION_JSON_VALUE,
                "text/html;q=0.3,application/json;q=0.9",
                MediaType.ALL_VALUE,
                MediaType.APPLICATION_XML_VALUE,
                "invalid"
        }) {
            HttpResponse<String> json = request(LIMITED_CODE, accept, "GET");
            assertCommonRateLimitHeaders(json, MediaType.APPLICATION_JSON_VALUE, accept);
            assertThat(jsonMapper.readValue(json.body(), ErrorResponse.class)).isEqualTo(
                    new ErrorResponse(
                            "RATE_LIMIT_EXCEEDED",
                            "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요",
                            REQUEST_ID
                    )
            );
        }

        for (String accept : new String[]{
                MediaType.TEXT_HTML_VALUE,
                MediaType.APPLICATION_JSON_VALUE
        }) {
            HttpResponse<String> head = request(LIMITED_CODE, accept, "HEAD");
            assertCommonRateLimitHeaders(head, accept, accept);
            assertThat(head.body()).isEmpty();
        }

        verify(useCase, times(1)).resolveLink(FIRST_CODE);
    }

    private void assertCommonRateLimitHeaders(
            HttpResponse<String> response,
            String contentType,
            String accept
    ) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).contains("15");
        assertThat(response.headers().firstValue(HttpHeaders.VARY)).contains(HttpHeaders.ACCEPT);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(value -> assertThat(MediaType.parseMediaType(value)
                                .isCompatibleWith(MediaType.parseMediaType(contentType)))
                        .as("Accept=%s, Content-Type=%s", accept, value)
                        .isTrue());
        assertThat(response.headers().firstValue("X-Request-Id")).contains(REQUEST_ID);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
    }

    private HttpResponse<String> request(String code, String accept, String method) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/l/" + code)
                    )
                    .header(HttpHeaders.ACCEPT, accept)
                    .header("X-Forwarded-For", code.equals(FIRST_CODE)
                            ? "198.51.100.1" : "198.51.100.2")
                    .header("X-Request-Id", REQUEST_ID)
                    .timeout(Duration.ofSeconds(5))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
