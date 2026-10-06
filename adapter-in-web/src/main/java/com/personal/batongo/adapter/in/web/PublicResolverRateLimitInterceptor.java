package com.personal.batongo.adapter.in.web;

import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class PublicResolverRateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final PublicResolverRateLimiter rateLimiter;

    private final PublicResolverQuotaPort distributedQuota;

    public PublicResolverRateLimitInterceptor(PublicResolverRateLimiter rateLimiter,
                                              ObjectProvider<PublicResolverQuotaPort> distributedQuota) {
        this.rateLimiter = rateLimiter;
        this.distributedQuota = distributedQuota.getIfAvailable();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/l/{code}")
                .includeHttpMethods(HttpMethod.GET, HttpMethod.HEAD);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        // 로컬 제한을 통과한 요청만 분산 할당량을 쓴다.
        long retryAfter = rateLimiter.acquireRetryAfterSeconds();
        if (retryAfter == 0 && distributedQuota != null) {
            retryAfter = distributedQuota.acquireRetryAfterSeconds();
        }
        if (retryAfter > 0) {
            throw new PublicResolverRateLimitExceededException(retryAfter);
        }
        return true;
    }
}
