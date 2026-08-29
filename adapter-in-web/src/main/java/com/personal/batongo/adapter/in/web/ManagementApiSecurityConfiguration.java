package com.personal.batongo.adapter.in.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ManagementApiSecurityConfiguration {

    static final String LINK_CREATE_AUTHORITY = "SCOPE_baton-go.links.create";
    static final String LINK_READ_AUTHORITY = "SCOPE_baton-go.links.read";
    static final String LINK_REVOKE_AUTHORITY = "SCOPE_baton-go.links.revoke";
    static final String TARGET_CONTRACT_OPERATE_AUTHORITY =
            "SCOPE_baton-go.target-contract.operate";

    @Bean
    SecurityFilterChain managementApiSecurityFilterChain(
            HttpSecurity http,
            FilterErrorResponseWriter errorResponseWriter
    ) throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                errorResponseWriter.write(
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED.value(),
                        "MANAGEMENT_AUTHENTICATION_REQUIRED",
                        "유효한 관리 JWT가 필요합니다"
                );
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
                        .requestMatchers(HttpMethod.PUT, "/api/v1/links/*/revocation")
                        .hasAuthority(LINK_REVOKE_AUTHORITY)
                        .requestMatchers("/api/v1/operations/**")
                        .hasAuthority(TARGET_CONTRACT_OPERATE_AUTHORITY)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
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
