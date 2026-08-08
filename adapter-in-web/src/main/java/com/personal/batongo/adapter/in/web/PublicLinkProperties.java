package com.personal.batongo.adapter.in.web;

import com.personal.batongo.domain.link.HttpOrigin;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go")
public record PublicLinkProperties(
        URI publicBaseUrl
) {

    public PublicLinkProperties {
        HttpOrigin origin = HttpOrigin.require(publicBaseUrl, "공개 base URL");
        if (!origin.isLoopback() && !origin.isHttps()) {
            throw new IllegalArgumentException(
                    "비로컬 공개 base URL은 HTTPS origin이어야 합니다"
            );
        }
        publicBaseUrl = origin.value();
    }

    public URI shortUrl(String rawCode) {
        return publicBaseUrl.resolve("/l/" + rawCode);
    }
}
