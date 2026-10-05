package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({", baton-go", "baton-go-management, baton-go-management"})
    @DisplayName("관리 JWT 대상을 생략하면 기본값으로, 명시하면 그 값으로 시작한다")
    void acceptsDefaultOrConfiguredAudience(String configured, String expected) {
        var runner = configured == null ? contextRunner
                : contextRunner.withPropertyValues("BATON_GO_MANAGEMENT_JWT_AUDIENCE=" + configured);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OAuth2ResourceServerProperties.class).getJwt().getAudiences())
                    .containsExactly(expected);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BATON_GO_MANAGEMENT_JWT_AUDIENCE=",
            "BATON_GO_MANAGEMENT_JWT_AUDIENCE=   ",
            "spring.security.oauth2.resourceserver.jwt.audiences=",
            "spring.security.oauth2.resourceserver.jwt.audiences=baton-go,"
    })
    @DisplayName("관리 JWT 대상 목록이 비어 있거나 빈 항목을 포함하면 시작을 거부한다")
    void rejectsEmptyAudienceConfiguration(String property) {
        contextRunner.withPropertyValues(property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("운영 HTTPS 주소와 개발용 루프백 HTTP 주소를 허용한다")
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

    @ParameterizedTest
    @CsvSource({
            "http://identity.example/issuer, https://identity.example/issuer/jwks",
            "https://identity.example/issuer, http://identity.example/issuer/jwks",
            "https://identity.example/issuer,"
    })
    @DisplayName("운영 HTTP 발급자·JWK Set이나 명시적인 JWK Set이 없는 관리 JWT 설정은 시작 단계에서 거부한다")
    void rejectsRemoteHttpOrMissingEndpoints(String issuerUri, String jwkSetUri) {
        assertThatThrownBy(() -> validator(issuerUri, jwkSetUri)).isInstanceOf(IllegalArgumentException.class);
    }

    private ManagementJwtConfigurationValidator validator(String issuerUri, String jwkSetUri) {
        OAuth2ResourceServerProperties properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri(issuerUri);
        properties.getJwt().setJwkSetUri(jwkSetUri);
        properties.getJwt().setAudiences(List.of("baton-go"));
        return new ManagementJwtConfigurationValidator(properties);
    }
}
