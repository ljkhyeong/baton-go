package com.personal.batongo.adapter.in.web;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go")
public record PublicLinkProperties(
        URI publicBaseUrl
) {

    public PublicLinkProperties {
        Objects.requireNonNull(publicBaseUrl, "공개 base URL은 필수입니다");
        String scheme = publicBaseUrl.getScheme();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || publicBaseUrl.getHost() == null
                || publicBaseUrl.getUserInfo() != null
                || publicBaseUrl.getQuery() != null
                || publicBaseUrl.getFragment() != null
                || (publicBaseUrl.getPath() != null
                && !publicBaseUrl.getPath().isEmpty()
                && !publicBaseUrl.getPath().equals("/"))) {
            throw new IllegalArgumentException(
                    "공개 base URL은 경로가 없는 HTTP 또는 HTTPS origin이어야 합니다"
            );
        }
    }

    public URI shortUrl(String rawCode) {
        return publicBaseUrl.resolve("/l/" + rawCode);
    }
}
