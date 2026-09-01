package com.personal.batongo.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class PublicResolverWebMvcConfiguration implements WebMvcConfigurer {

    private final PublicResolverRateLimitInterceptor rateLimitInterceptor;

    public PublicResolverWebMvcConfiguration(
            PublicResolverRateLimitInterceptor rateLimitInterceptor
    ) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/l/{code}");
    }
}
