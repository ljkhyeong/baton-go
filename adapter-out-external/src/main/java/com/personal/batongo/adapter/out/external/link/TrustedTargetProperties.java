package com.personal.batongo.adapter.out.external.link;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.targets")
public record TrustedTargetProperties(
        URI batonBaseUrl,
        URI roundBaseUrl
) {

    public TrustedTargetProperties {
        batonBaseUrl = requireOrigin(batonBaseUrl, "BATON base URL");
        roundBaseUrl = requireOrigin(roundBaseUrl, "ROUND base URL");
    }

    private static URI requireOrigin(URI value, String name) {
        Objects.requireNonNull(value, name + "은 필수입니다");
        String scheme = value.getScheme();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || (value.getPath() != null
                && !value.getPath().isEmpty()
                && !value.getPath().equals("/"))) {
            throw new IllegalArgumentException(name + "은 경로가 없는 HTTP 또는 HTTPS origin이어야 합니다");
        }
        return value;
    }
}
