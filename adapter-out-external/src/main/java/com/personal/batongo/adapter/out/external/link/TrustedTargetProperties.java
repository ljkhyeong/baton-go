package com.personal.batongo.adapter.out.external.link;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
                || hasInvalidExplicitPort(value)
                || (value.getPath() != null
                && !value.getPath().isEmpty()
                && !value.getPath().equals("/"))) {
            throw new IllegalArgumentException(name + "은 경로가 없는 HTTP 또는 HTTPS origin이어야 합니다");
        }
        return value;
    }

    private static boolean hasInvalidExplicitPort(URI value) {
        int port = value.getPort();
        String authority = value.getRawAuthority();
        return port == 0
                || port > 65_535
                || (port == -1 && authority != null && authority.endsWith(":"));
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
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        String literal = stripIpv6Brackets(host);
        if (literal.indexOf(':') < 0) {
            return isIpv4LoopbackLiteral(literal);
        }
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isIpv4LoopbackLiteral(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int firstOctet = -1;
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (!isCanonicalDecimalOctet(octet)) {
                return false;
            }
            int value = Integer.parseInt(octet);
            if (index == 0) {
                firstOctet = value;
            }
        }
        return firstOctet == 127;
    }

    private static boolean isCanonicalDecimalOctet(String value) {
        if (value.isEmpty()
                || value.length() > 3
                || value.length() > 1 && value.charAt(0) == '0') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        try {
            return Integer.parseInt(value) <= 255;
        } catch (NumberFormatException exception) {
            return false;
        }
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
