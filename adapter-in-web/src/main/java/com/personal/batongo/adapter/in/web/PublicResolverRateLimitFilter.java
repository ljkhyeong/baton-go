package com.personal.batongo.adapter.in.web;

import com.personal.batongo.adapter.in.web.PublicResolverRateLimiter.RateLimitDecision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@Order(RequestIdFilter.ORDER + 5)
public class PublicResolverRateLimitFilter extends OncePerRequestFilter {

    private static final PathPattern PUBLIC_RESOLVER_PATH =
            PathPatternParser.defaultInstance.parse("/l/{code}");

    private final PublicResolverRateLimiter rateLimiter;
    private final FilterErrorResponseWriter errorResponseWriter;

    public PublicResolverRateLimitFilter(
            PublicResolverRateLimiter rateLimiter,
            FilterErrorResponseWriter errorResponseWriter
    ) {
        this.rateLimiter = rateLimiter;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())
                && !HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        try {
            return !PUBLIC_RESOLVER_PATH.matches(
                    ServletRequestPathUtils.parse(request).pathWithinApplication()
            );
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Referrer-Policy", "no-referrer");
        RateLimitDecision decision = rateLimiter.acquire();
        if (!decision.allowed()) {
            response.setHeader(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(decision.retryAfterSeconds())
            );
            errorResponseWriter.write(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "RATE_LIMIT_EXCEEDED",
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }
}
