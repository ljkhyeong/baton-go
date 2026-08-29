package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.HttpOrigin;
import com.personal.batongo.domain.link.TrustedTarget;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.targets")
public final class TrustedTargetProperties implements TargetUrlPort {

    private final HttpOrigin batonOrigin;
    private final HttpOrigin roundOrigin;

    public TrustedTargetProperties(URI batonBaseUrl, URI roundBaseUrl) {
        this.batonOrigin = HttpOrigin.require(batonBaseUrl, "BATON base URL");
        this.roundOrigin = HttpOrigin.require(roundBaseUrl, "ROUND base URL");
        requireDeploymentTopology(batonOrigin, roundOrigin);
    }

    public HttpOrigin batonOrigin() {
        return batonOrigin;
    }

    @Override
    public URI resolve(TrustedTarget target) {
        HttpOrigin origin = switch (target.targetSystem()) {
            case BATON -> batonOrigin;
            case ROUND -> roundOrigin;
        };
        return origin.resolve(target.targetPath());
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
