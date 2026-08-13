package com.personal.batongo.domain.link;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
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
        return new HttpOrigin(canonicalize(value));
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

    private static URI canonicalize(URI value) {
        String scheme = value.getScheme().toLowerCase(Locale.ROOT);
        String host = canonicalHost(value.getHost());
        int port = value.getPort();
        if (port == defaultPort(scheme)) {
            port = -1;
        }
        try {
            return new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("HTTP origin을 정규화할 수 없습니다", exception);
        }
    }

    private static String canonicalHost(String host) {
        String literal = stripIpv6Brackets(host);
        if (literal.indexOf(':') >= 0 && literal.indexOf('%') < 0) {
            try {
                InetAddress address = InetAddress.getByName(literal);
                if (address.getAddress().length == 16) {
                    return address.getHostAddress().toLowerCase(Locale.ROOT);
                }
            } catch (UnknownHostException ignored) {
                // URI가 허용한 미래 주소 표현은 원문 host의 대소문자만 정규화한다.
            }
        }
        return literal.toLowerCase(Locale.ROOT);
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
            return InetAddress.getByName(leftLiteral)
                    .equals(InetAddress.getByName(rightLiteral));
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
            int parsed = parseCanonicalDecimalOctet(octets[index]);
            if (parsed < 0) {
                return false;
            }
            if (index == 0) {
                firstOctet = parsed;
            }
        }
        return firstOctet == 127;
    }

    private static int parseCanonicalDecimalOctet(String value) {
        if (value.isEmpty()
                || value.length() > 3) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed <= 255 && Integer.toString(parsed).equals(value)
                    ? parsed
                    : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return defaultPort(value.getScheme());
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
