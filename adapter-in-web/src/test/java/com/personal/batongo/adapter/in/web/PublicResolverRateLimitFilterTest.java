package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration;
import tools.jackson.databind.ObjectMapper;

class PublicResolverRateLimitFilterTest {

    private static final String VALID_CODE = "VOvLShvx93kQpj8x7w2HYQ";
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    @DisplayName("코드 형식과 전달 IP에 관계없이 공개 resolver 요청은 같은 버킷을 공유한다")
    void sharesAggregateBucketAcrossResolverCodesAndForwardedAddresses() throws Exception {
        PublicResolverRateLimitFilter filter = filter(1, Duration.ofSeconds(30));
        AtomicInteger downstreamCalls = new AtomicInteger();

        MockHttpServletRequest firstRequest = request("GET", "/l/not-a-valid-code");
        firstRequest.addHeader("X-Forwarded-For", "198.51.100.1");
        MockHttpServletResponse firstResponse = invoke(filter, firstRequest, downstreamCalls);
        MockHttpServletRequest secondRequest = request("GET", "/l/" + VALID_CODE);
        secondRequest.addHeader("X-Forwarded-For", "198.51.100.2");
        MockHttpServletResponse secondResponse = invoke(
                filter,
                secondRequest,
                downstreamCalls
        );

        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(secondResponse.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(secondResponse.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(secondResponse.getHeader("X-Request-Id")).isNotBlank();
        assertThat(secondResponse.getContentAsString())
                .contains("\"code\":\"RATE_LIMIT_EXCEEDED\"")
                .contains("\"requestId\":");
        assertThat(downstreamCalls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html"})
    @DisplayName("공개 resolver HEAD가 한도를 초과하면 본문 없이 재시도 헤더를 반환한다")
    void returnsHeaderOnlyRateLimitResponseForHead(String accept) throws Exception {
        PublicResolverRateLimitFilter filter = filter(1, Duration.ofSeconds(15));
        AtomicInteger downstreamCalls = new AtomicInteger();

        invoke(filter, request("GET", "/l/first"), downstreamCalls);
        MockHttpServletRequest request = request("HEAD", "/l/second");
        request.addHeader(HttpHeaders.ACCEPT, accept);
        MockHttpServletResponse response = invoke(filter, request, downstreamCalls);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("15");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(
                MediaType.parseMediaType(accept)
        )).isTrue();
        assertThat(downstreamCalls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json;q=0.3,text/html;q=0.9"})
    @DisplayName("HTML을 우선하는 공개 요청 제한 응답은 대기 시간과 요청 번호를 안전하게 표시한다")
    void rendersRateLimitPageForBrowser(String accept) throws Exception {
        PublicResolverRateLimitFilter filter = filter(1, Duration.ofSeconds(30));
        AtomicInteger downstreamCalls = new AtomicInteger();
        invoke(filter, request("GET", "/l/first"), downstreamCalls);
        MockHttpServletRequest request = request("GET", "/l/" + VALID_CODE);
        request.addHeader(HttpHeaders.ACCEPT, accept);
        request.addHeader("X-Request-Id", "public-rate-limit-page");

        MockHttpServletResponse response = invoke(filter, request, downstreamCalls);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeaders(HttpHeaders.RETRY_AFTER)).containsExactly("30");
        assertThat(response.getHeader(HttpHeaders.VARY)).isEqualTo(HttpHeaders.ACCEPT);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getContentAsString()).contains(
                "<html lang=\"ko\">", "30초 후에 다시 열어 주세요", "<code>public-rate-limit-page</code>"
        ).doesNotContain(VALID_CODE, "<script", "http-equiv=\"refresh\"");
        assertThat(downstreamCalls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "text/html;q=0.3,application/json;q=0.9", "application/xml", "invalid"})
    @DisplayName("JSON 우선·기본·비지원·잘못된 Accept의 요청 제한은 기존 JSON 429로 끝난다")
    void keepsJsonRateLimitResponse(String accept) throws Exception {
        PublicResolverRateLimitFilter filter = filter(1, Duration.ofSeconds(30));
        AtomicInteger downstreamCalls = new AtomicInteger();
        invoke(filter, request("GET", "/l/first"), downstreamCalls);
        MockHttpServletRequest request = request("GET", "/l/second");
        request.addHeader(HttpHeaders.ACCEPT, accept);

        MockHttpServletResponse response = invoke(filter, request, downstreamCalls);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMIT_EXCEEDED\"");
        assertThat(downstreamCalls).hasValue(1);
    }

    @Test
    @DisplayName("정확한 공개 resolver GET과 HEAD 이외의 요청은 버킷을 소비하지 않는다")
    void limitsOnlyExactResolverGetAndHeadRequests() throws Exception {
        PublicResolverRateLimitFilter filter = filter(1, Duration.ofMinutes(1));
        AtomicInteger downstreamCalls = new AtomicInteger();

        invoke(filter, request("POST", "/l/post-code"), downstreamCalls);
        invoke(filter, request("GET", "/l/extra/segment"), downstreamCalls);
        invoke(filter, request("GET", "/unknown"), downstreamCalls);
        invoke(
                filter,
                request("GET", "/l/first"),
                downstreamCalls
        );
        MockHttpServletResponse secondResolver = invoke(
                filter,
                request("HEAD", "/l/second"),
                downstreamCalls
        );

        assertThat(secondResolver.getStatus()).isEqualTo(429);
        assertThat(downstreamCalls).hasValue(4);
    }

    private PublicResolverRateLimitFilter filter(long capacity, Duration window) throws IOException {
        PublicResolverRateLimiter limiter = new PublicResolverRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new PublicResolverRateLimitProperties(capacity, window)
        );
        var mvcConfiguration = new DelegatingWebMvcConfiguration();
        mvcConfiguration.setConfigurers(List.of(new WebMvcConfiguration()));
        return new PublicResolverRateLimitFilter(
                limiter,
                new FilterErrorResponseWriter(new ObjectMapper()),
                new PublicLinkErrorPage(),
                mvcConfiguration.mvcContentNegotiationManager()
        );
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private MockHttpServletResponse invoke(
            PublicResolverRateLimitFilter rateLimitFilter,
            MockHttpServletRequest request,
            AtomicInteger downstreamCalls
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestIdFilter requestIdFilter = new RequestIdFilter();
        FilterChain downstream = (servletRequest, servletResponse) ->
                downstreamCalls.incrementAndGet();

        requestIdFilter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> rateLimitFilter.doFilter(
                        servletRequest,
                        servletResponse,
                        downstream
                )
        );
        return response;
    }
}
