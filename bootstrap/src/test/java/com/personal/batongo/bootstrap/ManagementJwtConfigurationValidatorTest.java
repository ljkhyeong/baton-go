package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ManagementJwtConfigurationValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
            .withUserConfiguration(ManagementJwtConfigurationValidator.class)
            .withPropertyValues(
                    "BATON_GO_MANAGEMENT_JWT_ISSUER_URI=https://identity.example/issuer",
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://identity.example/issuer/jwks"
            );

    @Test
    @DisplayName("관리 JWT audience를 생략하면 기본값으로 시작한다")
    void acceptsDefaultAudience() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OAuth2ResourceServerProperties.class).getJwt().getAudiences())
                    .containsExactly("baton-go");
        });
    }

    @Test
    @DisplayName("명시한 관리 JWT audience로 시작한다")
    void acceptsConfiguredAudience() {
        contextRunner.withPropertyValues("BATON_GO_MANAGEMENT_JWT_AUDIENCE=baton-go-management")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OAuth2ResourceServerProperties.class).getJwt().getAudiences())
                            .containsExactly("baton-go-management");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BATON_GO_MANAGEMENT_JWT_AUDIENCE=",
            "BATON_GO_MANAGEMENT_JWT_AUDIENCE=   ",
            "spring.security.oauth2.resourceserver.jwt.audiences=",
            "spring.security.oauth2.resourceserver.jwt.audiences=baton-go,"
    })
    @DisplayName("관리 JWT audience 목록이 비어 있거나 빈 항목을 포함하면 시작을 거부한다")
    void rejectsEmptyAudienceConfiguration(String property) {
        contextRunner.withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("HTTPS 운영 endpoint와 loopback HTTP 개발 endpoint를 허용한다")
    void acceptsSecureAndLocalEndpoints() {
        assertThatCode(() -> validator(
                "https://identity.example/issuer",
                "https://identity.example/issuer/jwks"
        )).doesNotThrowAnyException();
        assertThatCode(() -> validator(
                "http://localhost:9000/issuer",
                "http://127.0.0.1:9000/issuer/jwks"
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비로컬 HTTP 관리 JWT 발급자를 시작 단계에서 거부한다")
    void rejectsRemoteHttpIssuer() {
        assertThatThrownBy(() -> validator(
                "http://identity.example/issuer",
                "https://identity.example/issuer/jwks"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비로컬 HTTP 관리 JWT JWK Set을 시작 단계에서 거부한다")
    void rejectsRemoteHttpJwkSet() {
        assertThatThrownBy(() -> validator(
                "https://identity.example/issuer",
                "http://identity.example/issuer/jwks"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("명시적인 관리 JWT JWK Set이 없으면 시작 단계에서 거부한다")
    void rejectsMissingJwkSet() {
        assertThatThrownBy(() -> validator(
                "https://identity.example/issuer",
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private ManagementJwtConfigurationValidator validator(String issuerUri, String jwkSetUri) {
        OAuth2ResourceServerProperties properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri(issuerUri);
        properties.getJwt().setJwkSetUri(jwkSetUri);
        properties.getJwt().setAudiences(List.of("baton-go"));
        return new ManagementJwtConfigurationValidator(properties);
    }
}
