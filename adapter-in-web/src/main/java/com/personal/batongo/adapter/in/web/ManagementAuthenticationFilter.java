package com.personal.batongo.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ManagementAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_SCHEME = "Bearer";
    private static final PathPattern MANAGEMENT_API_PATH =
            PathPatternParser.defaultInstance.parse("/api/v1/**");

    private final byte[] expectedToken;
    private final FilterErrorResponseWriter errorResponseWriter;

    public ManagementAuthenticationFilter(
            ManagementProperties properties,
            FilterErrorResponseWriter errorResponseWriter
    ) {
        this.expectedToken = properties.token().getBytes(StandardCharsets.UTF_8);
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        try {
            return !MANAGEMENT_API_PATH.matches(
                    ServletRequestPathUtils.parse(request).pathWithinApplication()
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!matches(authorization)) {
            errorResponseWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED.value(),
                    "MANAGEMENT_AUTHENTICATION_REQUIRED",
                    "유효한 관리 credential이 필요합니다"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String authorization) {
        if (authorization == null || !authorization.regionMatches(
                true,
                0,
                BEARER_SCHEME,
                0,
                BEARER_SCHEME.length()
        )) {
            return false;
        }

        int credentialStart = BEARER_SCHEME.length();
        if (credentialStart >= authorization.length()
                || authorization.charAt(credentialStart) != ' ') {
            return false;
        }
        while (credentialStart < authorization.length()
                && authorization.charAt(credentialStart) == ' ') {
            credentialStart++;
        }

        byte[] presented = authorization.substring(credentialStart)
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, presented);
    }
}
