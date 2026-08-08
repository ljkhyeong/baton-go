package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.domain.link.HttpOrigin;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.targets")
public record TrustedTargetProperties(
        URI batonBaseUrl,
        URI roundBaseUrl
) {

    public TrustedTargetProperties {
        HttpOrigin batonOrigin = HttpOrigin.require(batonBaseUrl, "BATON base URL");
        HttpOrigin roundOrigin = HttpOrigin.require(roundBaseUrl, "ROUND base URL");
        requireDeploymentTopology(batonOrigin, roundOrigin);
        batonBaseUrl = batonOrigin.value();
        roundBaseUrl = roundOrigin.value();
    }

    private static void requireDeploymentTopology(
            HttpOrigin batonOrigin,
            HttpOrigin roundOrigin
    ) {
        boolean batonLoopback = batonOrigin.isLoopback();
        boolean roundLoopback = roundOrigin.isLoopback();
        if (batonLoopback && roundLoopback) {
            return;
        }
        if (batonLoopback
                || roundLoopback
                || !batonOrigin.isHttps()
                || !roundOrigin.isHttps()
                || !batonOrigin.sameOrigin(roundOrigin)) {
            throw new IllegalArgumentException(
                    "비로컬 BATON·ROUND base URL은 동일한 HTTPS origin이어야 합니다"
            );
        }
    }
}
