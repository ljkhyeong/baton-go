package com.personal.batongo.bootstrap;

import com.personal.batongo.domain.link.HttpOrigin;
import java.net.URI;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 시작할 때 관리 JWT 발급자·JWK 주소의 HTTPS 사용과 audience 설정을 확인합니다. */
@Component
public class ManagementJwtConfigurationValidator {

    public ManagementJwtConfigurationValidator(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        requireSecureEndpoint(jwt.getIssuerUri(), "관리 JWT issuer URI");
        requireSecureEndpoint(jwt.getJwkSetUri(), "관리 JWT JWK Set URI");
        Assert.notEmpty(jwt.getAudiences(), "관리 JWT audience는 필수입니다");
        jwt.getAudiences().forEach(audience ->
                Assert.hasText(audience, "관리 JWT audience에는 빈 값을 설정할 수 없습니다")
        );
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
                    name + "는 HTTPS여야 합니다. 루프백 HTTP는 로컬 개발에서만 사용할 수 있습니다"
            );
        }
    }
}
