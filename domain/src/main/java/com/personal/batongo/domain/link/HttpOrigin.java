package com.personal.batongo.domain.link;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

/** HTTP(S) 출처의 형식과 브라우저의 동일 출처 판정 규칙을 관리합니다. */
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
                    name + "은 경로가 없는 HTTP 또는 HTTPS 출처여야 합니다"
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
            InetAddress address = InetAddress.getByName(literal);
            return address instanceof Inet6Address && address.isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    public boolean sameOrigin(HttpOrigin other) {
        return value.equals(other.value);
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
        if (port == ("https".equals(scheme) ? 443 : 80)) {
            port = -1;
        }
        try {
            return new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("HTTP 출처를 정규 형식으로 바꿀 수 없습니다", exception);
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
                // JDK가 허용하는 다른 주소 형식은 호스트의 대소문자만 정리한다.
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

}
