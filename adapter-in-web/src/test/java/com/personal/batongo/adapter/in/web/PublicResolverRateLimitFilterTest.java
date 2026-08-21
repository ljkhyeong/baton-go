package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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

        assertThat(firstResponse.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(secondResponse.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(secondResponse.getHeader("X-Request-Id")).isNotBlank();
        assertThat(secondResponse.getContentAsString())
                .contains("\"code\":\"RATE_LIMIT_EXCEEDED\"")
                .contains("\"requestId\":");
        assertThat(downstreamCalls).hasValue(1);
    }

    @Test
    @DisplayName("공개 resolver HEAD가 한도를 초과하면 본문 없이 재시도 헤더를 반환한다")
    void returnsHeaderOnlyRateLimitResponseForHead() throws Exception {
        PublicResolverRateLimitFilter filter = filter(1, Duration.ofSeconds(15));
        AtomicInteger downstreamCalls = new AtomicInteger();

        invoke(filter, request("GET", "/l/first"), downstreamCalls);
        MockHttpServletResponse response = invoke(
                filter,
                request("HEAD", "/l/second"),
                downstreamCalls
        );

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("15");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(response.getContentAsByteArray()).isEmpty();
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

    private PublicResolverRateLimitFilter filter(long capacity, Duration window) {
        PublicResolverRateLimiter limiter = new PublicResolverRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new PublicResolverRateLimitProperties(capacity, window)
        );
        return new PublicResolverRateLimitFilter(
                limiter,
                new FilterErrorResponseWriter(new ObjectMapper())
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
