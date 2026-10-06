package com.personal.batongo.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "requestId";
    private static final String HEADER_NAME = "X-Request-Id";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String candidate = request.getHeader(HEADER_NAME);
        String requestId = candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
        response.setHeader(HEADER_NAME, requestId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");

        // 비동기 처리를 쓰지 않아 요청 스레드의 MDC가 응답 완료까지 요청 ID를 전달한다.
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, requestId)) {
            filterChain.doFilter(request, response);
        }
    }

    static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}
