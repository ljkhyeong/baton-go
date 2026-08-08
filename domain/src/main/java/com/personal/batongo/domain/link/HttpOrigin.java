package com.personal.batongo.domain.link;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/** HTTP(S) origin의 구조와 browser origin 비교 규칙을 한 곳에서 보존합니다. */
public final class HttpOrigin {

    private final URI value;

    private HttpOrigin(URI value) {
        this.value = value;
    }

    public static HttpOrigin require(URI value, String name) {
        Objects.requireNonNull(value, name + "은 필수입니다");
        String scheme = value.getScheme();
        String rawPath = value.getRawPath();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || hasInvalidExplicitPort(value)
                || rawPath != null && !rawPath.isEmpty() && !rawPath.equals("/")) {
            throw new IllegalArgumentException(
                    name + "은 경로가 없는 HTTP 또는 HTTPS origin이어야 합니다"
            );
        }
        return new HttpOrigin(value);
    }

    public URI value() {
        return value;
    }

    public boolean isHttps() {
        return "https".equalsIgnoreCase(value.getScheme());
    }

    public boolean isLoopback() {
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

    public boolean sameOrigin(HttpOrigin other) {
        return sameOrigin(other.value);
    }

    public boolean sameOrigin(URI other) {
        return other != null
                && other.getScheme() != null
                && other.getHost() != null
                && value.getScheme().equalsIgnoreCase(other.getScheme())
                && sameHost(value.getHost(), other.getHost())
                && effectivePort(value) == effectivePort(other);
    }

    public URI resolve(String path) {
        return value.resolve(path);
    }

    private static boolean hasInvalidExplicitPort(URI value) {
        int port = value.getPort();
        String authority = value.getRawAuthority();
        return port == 0
                || port > 65_535
                || port == -1 && authority != null && authority.endsWith(":");
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean sameHost(String left, String right) {
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        String leftLiteral = stripIpv6Brackets(left);
        String rightLiteral = stripIpv6Brackets(right);
        if (leftLiteral.indexOf(':') < 0
                || rightLiteral.indexOf(':') < 0
                || leftLiteral.indexOf('%') >= 0
                || rightLiteral.indexOf('%') >= 0) {
            return false;
        }
        try {
            return Arrays.equals(
                    InetAddress.getByName(leftLiteral).getAddress(),
                    InetAddress.getByName(rightLiteral).getAddress()
            );
        } catch (UnknownHostException exception) {
            return false;
        }
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
            int parsed = Integer.parseInt(octet);
            if (index == 0) {
                firstOctet = parsed;
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

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }
}
