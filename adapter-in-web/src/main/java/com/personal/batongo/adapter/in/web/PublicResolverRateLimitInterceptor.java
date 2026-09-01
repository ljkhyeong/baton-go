package com.personal.batongo.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PublicResolverRateLimitInterceptor implements HandlerInterceptor {

    private final PublicResolverRateLimiter rateLimiter;

    public PublicResolverRateLimitInterceptor(PublicResolverRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!HttpMethod.GET.matches(request.getMethod())
                && !HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }

        PublicResolverRateLimiter.RateLimitDecision decision = rateLimiter.acquire();
        if (!decision.allowed()) {
            throw new PublicResolverRateLimitExceededException(decision.retryAfterSeconds());
        }
        return true;
    }
}
