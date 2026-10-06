package com.personal.batongo.adapter.in.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(ManagementJwkProperties.class)
public class ManagementApiSecurityConfiguration {

    static final String LINK_CREATE_AUTHORITY = "SCOPE_baton-go.links.create";
    static final String LINK_READ_AUTHORITY = "SCOPE_baton-go.links.read";
    static final String LINK_REVOKE_AUTHORITY = "SCOPE_baton-go.links.revoke";

    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer managementJwkHttpClient(ManagementJwkProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        var restOperations = new RestTemplate(requestFactory);
        return builder -> builder.restOperations(restOperations);
    }

    @Bean
    JwtTimestampValidator managementJwtTimestampValidator() {
        var validator = new JwtTimestampValidator();
        validator.setAllowEmptyExpiryClaim(false);
        return validator;
    }

    @Bean
    OAuth2TokenValidator<Jwt> managementJwtSubjectValidator() {
        return new JwtClaimValidator<String>("sub", StringUtils::hasText);
    }

    @Bean
    SecurityFilterChain managementApiSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver
    ) throws Exception {
        // 인증·권한 오류도 MVC 예외 처리기(GlobalExceptionHandler)의 공통 오류 형식으로 응답한다.
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                exceptionResolver.resolveException(request, response, null, exception);
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                exceptionResolver.resolveException(request, response, null, exception);

        http.securityMatcher("/api/v1/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/links")
                        .hasAuthority(LINK_CREATE_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/links", "/api/v1/links/*")
                        .hasAuthority(LINK_READ_AUTHORITY)
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/links", "/api/v1/links/*")
                        .hasAuthority(LINK_READ_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/links/*/revocation")
                        .hasAuthority(LINK_REVOKE_AUTHORITY)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                // 기본 처리기는 JWK 조회 실패를 다시 던지므로 모든 인증 실패를 진입점으로 보낸다.
                                filter.setAuthenticationFailureHandler(authenticationEntryPoint::commence);
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
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
