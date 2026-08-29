package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;

class ManagementJwtTransportValidatorTest {

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

    private ManagementJwtTransportValidator validator(String issuerUri, String jwkSetUri) {
        OAuth2ResourceServerProperties properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri(issuerUri);
        properties.getJwt().setJwkSetUri(jwkSetUri);
        return new ManagementJwtTransportValidator(properties);
    }
}
