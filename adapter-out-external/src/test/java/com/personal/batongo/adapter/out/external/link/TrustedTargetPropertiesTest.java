package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrustedTargetPropertiesTest {

    @Test
    @DisplayName("로컬 개발의 서로 다른 loopback HTTP origin은 허용한다")
    void acceptsSeparateLoopbackOriginsForLocalDevelopment() {
        assertThatCode(() -> new TrustedTargetProperties(
                URI.create("http://localhost:5173"),
                URI.create("http://127.0.0.1:5174")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IPv4 loopback 대역과 확장 IPv6 loopback도 로컬 개발 origin으로 허용한다")
    void acceptsSemanticLoopbackAddressesForLocalDevelopment() {
        assertThatCode(() -> new TrustedTargetProperties(
                URI.create("http://127.0.0.2:5173"),
                URI.create("http://[0:0:0:0:0:0:0:1]:5174")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비로컬 target은 동일한 HTTPS origin이면 허용한다")
    void acceptsSameHttpsOriginOutsideLocalDevelopment() {
        assertThatCode(() -> new TrustedTargetProperties(
                URI.create("https://baton.example"),
                URI.create("https://BATON.example:443")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비로컬 target의 서로 다른 origin은 거부한다")
    void rejectsDifferentOriginsOutsideLocalDevelopment() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("https://baton.example"),
                URI.create("https://round.example")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비로컬 BATON·ROUND base URL은 동일한 HTTPS origin이어야 합니다");
    }

    @Test
    @DisplayName("비로컬 target의 HTTPS 포트가 다르면 같은 host여도 거부한다")
    void rejectsDifferentHttpsPortsOutsideLocalDevelopment() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("https://baton.example:443"),
                URI.create("https://baton.example:444")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비로컬 BATON·ROUND base URL은 동일한 HTTPS origin이어야 합니다");
    }

    @Test
    @DisplayName("비로컬 target의 HTTP origin은 거부한다")
    void rejectsHttpOriginsOutsideLocalDevelopment() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("http://baton.example"),
                URI.create("http://baton.example")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비로컬 BATON·ROUND base URL은 동일한 HTTPS origin이어야 합니다");
    }

    @Test
    @DisplayName("loopback과 비로컬 target을 섞은 설정은 거부한다")
    void rejectsMixedLoopbackAndRemoteOrigins() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("http://localhost:5173"),
                URI.create("https://baton.example")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비로컬 BATON·ROUND base URL은 동일한 HTTPS origin이어야 합니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1.example:5173",
            "http://0127.0.0.1:5173",
            "http://127.00.0.1:5173",
            "http://127.0.0.01:5173"
    })
    @DisplayName("모호하거나 loopback처럼 보이는 host에는 로컬 HTTP 예외를 적용하지 않는다")
    void rejectsAmbiguousOrLookalikeLoopbackHosts(String rawOrigin) {
        URI lookalike = URI.create(rawOrigin);

        assertThatThrownBy(() -> new TrustedTargetProperties(lookalike, lookalike))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비로컬 BATON·ROUND base URL은 동일한 HTTPS origin이어야 합니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://baton.example:",
            "http://127.0.0.1:",
            "https://[::1]:",
            "https://baton.example:0",
            "https://baton.example:65536"
    })
    @DisplayName("사용할 수 없는 명시적 포트는 HTTP origin으로 허용하지 않는다")
    void rejectsInvalidExplicitPorts(String rawOrigin) {
        URI origin = URI.create(rawOrigin);

        assertThatThrownBy(() -> new TrustedTargetProperties(origin, origin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경로가 없는 HTTP 또는 HTTPS origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user@baton.example",
            "https://baton.example/private",
            "https://baton.example?credential=value",
            "https://baton.example#credential"
    })
    @DisplayName("credential이나 경로 요소가 포함된 값은 신뢰 origin으로 허용하지 않는다")
    void rejectsOriginsWithAuthorityOrPathComponents(String rawOrigin) {
        URI origin = URI.create(rawOrigin);

        assertThatThrownBy(() -> new TrustedTargetProperties(origin, origin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경로가 없는 HTTP 또는 HTTPS origin");
    }
}
