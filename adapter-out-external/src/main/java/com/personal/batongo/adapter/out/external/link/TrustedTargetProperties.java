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
        requireDeploymentTopology(batonBaseUrl, roundBaseUrl);
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

    private static void requireDeploymentTopology(URI batonBaseUrl, URI roundBaseUrl) {
        boolean batonLoopback = isLoopback(batonBaseUrl);
        boolean roundLoopback = isLoopback(roundBaseUrl);
        if (batonLoopback && roundLoopback) {
            return;
        }
        if (batonLoopback
                || roundLoopback
                || !"https".equalsIgnoreCase(batonBaseUrl.getScheme())
                || !"https".equalsIgnoreCase(roundBaseUrl.getScheme())
                || !sameOrigin(batonBaseUrl, roundBaseUrl)) {
            throw new IllegalArgumentException(
                    "비로컬 BATON·ROUND base URL은 동일한 HTTPS origin이어야 합니다"
            );
        }
    }

    private static boolean isLoopback(URI value) {
        String host = value.getHost();
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }
}
