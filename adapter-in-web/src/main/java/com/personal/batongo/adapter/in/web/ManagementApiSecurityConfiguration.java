package com.personal.batongo.adapter.in.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ManagementApiSecurityConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ManagementApiSecurityConfiguration.class);

    static final String LINK_CREATE_AUTHORITY = "SCOPE_baton-go.links.create";
    static final String LINK_READ_AUTHORITY = "SCOPE_baton-go.links.read";
    static final String LINK_REVOKE_AUTHORITY = "SCOPE_baton-go.links.revoke";
    static final String TARGET_CONTRACT_OPERATE_AUTHORITY =
            "SCOPE_baton-go.target-contract.operate";

    @Bean
    JwtTimestampValidator managementJwtTimestampValidator() {
        var validator = new JwtTimestampValidator();
        validator.setAllowEmptyExpiryClaim(false);
        return validator;
    }

    @Bean
    SecurityFilterChain managementApiSecurityFilterChain(
            HttpSecurity http,
            FilterErrorResponseWriter errorResponseWriter,
            MeterRegistry meterRegistry
    ) throws Exception {
        Counter serviceFailures = meterRegistry.counter(
                "baton.go.management.authentication.service.failures"
        );
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) -> {
            if (exception instanceof AuthenticationServiceException) {
                serviceFailures.increment();
                LOG.error(
                        "관리 JWT 검증 서비스 오류 requestId={} exceptionType={}",
                        RequestIdFilter.requestId(request),
                        exception.getClass().getName()
                );
                errorResponseWriter.write(
                        request,
                        response,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_ERROR",
                        "서버에서 요청을 처리하지 못했습니다"
                );
                return;
            }
            errorResponseWriter.write(
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED.value(),
                    "MANAGEMENT_AUTHENTICATION_REQUIRED",
                    "유효한 관리 JWT가 필요합니다"
            );
        };
        AuthenticationEntryPointFailureHandler failureHandler =
                new AuthenticationEntryPointFailureHandler(authenticationEntryPoint);
        failureHandler.setRethrowAuthenticationServiceException(false);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                errorResponseWriter.write(
                        request,
                        response,
                        HttpStatus.FORBIDDEN.value(),
                        "MANAGEMENT_AUTHORIZATION_REQUIRED",
                        "요청한 관리 작업 권한이 필요합니다"
                );

        http.securityMatcher("/api/v1/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/links")
                        .hasAuthority(LINK_CREATE_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/links/*")
                        .hasAuthority(LINK_READ_AUTHORITY)
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/links/*")
                        .hasAuthority(LINK_READ_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/links/*/revocation")
                        .hasAuthority(LINK_REVOKE_AUTHORITY)
                        .requestMatchers("/api/v1/operations/**")
                        .hasAuthority(TARGET_CONTRACT_OPERATE_AUTHORITY)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(failureHandler);
                                return filter;
                            }
                        })
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .requestCache(cache -> cache.disable())
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
