package com.personal.batongo.bootstrap;

import com.personal.batongo.domain.link.HttpOrigin;
import java.net.URI;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.stereotype.Component;

/** 관리 JWT 발급자와 JWK Set endpoint의 운영 전송 경계를 시작 단계에서 검증합니다. */
@Component
public class ManagementJwtTransportValidator {

    public ManagementJwtTransportValidator(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        requireSecureEndpoint(jwt.getIssuerUri(), "관리 JWT issuer URI");
        requireSecureEndpoint(jwt.getJwkSetUri(), "관리 JWT JWK Set URI");
    }

    private static void requireSecureEndpoint(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 필수입니다");
        }
        HttpOrigin origin;
        try {
            URI endpoint = URI.create(value);
            origin = HttpOrigin.require(endpoint.resolve("/"), name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    name + "는 절대 HTTP 또는 HTTPS URI여야 합니다"
            );
        }
        if (!origin.isHttps() && !origin.isLoopback()) {
            throw new IllegalArgumentException(
                    name + "는 HTTPS여야 하며 로컬 개발에서만 loopback HTTP를 사용할 수 있습니다"
            );
        }
    }
}
