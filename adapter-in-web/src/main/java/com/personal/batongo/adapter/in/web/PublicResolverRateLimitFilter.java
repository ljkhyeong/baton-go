package com.personal.batongo.adapter.in.web;

import com.personal.batongo.adapter.in.web.PublicResolverRateLimiter.RateLimitDecision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.condition.ProducesRequestCondition;
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
    private final PublicLinkErrorPage errorPage;
    private final ProducesRequestCondition htmlCondition;
    private final ProducesRequestCondition jsonCondition;
    private final StringHttpMessageConverter htmlConverter =
            new StringHttpMessageConverter(StandardCharsets.UTF_8);

    public PublicResolverRateLimitFilter(
            PublicResolverRateLimiter rateLimiter,
            FilterErrorResponseWriter errorResponseWriter,
            PublicLinkErrorPage errorPage,
            ContentNegotiationManager contentNegotiationManager
    ) {
        this.rateLimiter = rateLimiter;
        this.errorResponseWriter = errorResponseWriter;
        this.errorPage = errorPage;
        this.htmlCondition = new ProducesRequestCondition(
                new String[]{MediaType.TEXT_HTML_VALUE}, null, contentNegotiationManager
        );
        this.jsonCondition = new ProducesRequestCondition(
                new String[]{MediaType.APPLICATION_JSON_VALUE}, null, contentNegotiationManager
        );
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
        RateLimitDecision decision = rateLimiter.acquire();
        if (!decision.allowed()) {
            String retryAfter = Long.toString(decision.retryAfterSeconds());
            String message = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요";
            if (prefersHtml(request)) {
                ResponseEntity<String> html = errorPage.render(ResponseEntity
                        .status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, retryAfter)
                        .body(new ErrorResponse(
                                "RATE_LIMIT_EXCEEDED",
                                message,
                                RequestIdFilter.requestId(request)
                        )));
                var output = new ServletServerHttpResponse(response);
                output.setStatusCode(html.getStatusCode());
                output.getHeaders().putAll(html.getHeaders());
                if (HttpMethod.HEAD.matches(request.getMethod())) {
                    output.flush();
                } else {
                    htmlConverter.write(html.getBody(), html.getHeaders().getContentType(), output);
                }
                return;
            }
            response.setHeader(HttpHeaders.RETRY_AFTER, retryAfter);
            response.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT);
            errorResponseWriter.write(
                    request,
                    response,
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "RATE_LIMIT_EXCEEDED",
                    message
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean prefersHtml(HttpServletRequest request) {
        ProducesRequestCondition html = htmlCondition.getMatchingCondition(request);
        ProducesRequestCondition json = jsonCondition.getMatchingCondition(request);
        return html != null && (json == null || html.compareTo(json, request) < 0);
    }
}
