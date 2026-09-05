package com.personal.batongo.adapter.in.web;

import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PublicResolverRateLimitInterceptor implements HandlerInterceptor {

    private final PublicResolverRateLimiter rateLimiter;

    private final PublicResolverQuotaPort distributedQuota;

    public PublicResolverRateLimitInterceptor(PublicResolverRateLimiter rateLimiter,
                                              ObjectProvider<PublicResolverQuotaPort> distributedQuota) {
        this.rateLimiter = rateLimiter;
        this.distributedQuota = distributedQuota.getIfAvailable();
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
        if (distributedQuota != null) {
            long retryAfter = distributedQuota.acquireRetryAfterSeconds();
            if (retryAfter > 0) throw new PublicResolverRateLimitExceededException(retryAfter);
        }
        return true;
    }
}
