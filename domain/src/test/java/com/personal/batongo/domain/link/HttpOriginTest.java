package com.personal.batongo.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpOriginTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080",
            "http://127.0.0.2:8080",
            "http://[0:0:0:0:0:0:0:1]:8080"
    })
    @DisplayName("표준 루프백 HTTP 출처를 정확히 판정한다")
    void recognizesCanonicalLoopbackOrigins(String rawOrigin) {
        assertThat(HttpOrigin.require(URI.create(rawOrigin), "origin").isLoopback()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://0127.0.0.1:8080",
            "http://127.00.0.1:8080",
            "http://127.0.0.1.example:8080",
            "http://[::ffff:127.0.0.1]:8080",
            "http://[::ffff:7f00:1]:8080",
            "https://go.example"
    })
    @DisplayName("모호한 주소와 운영 호스트는 루프백으로 분류하지 않는다")
    void rejectsAmbiguousLoopbackRepresentations(String rawOrigin) {
        assertThat(HttpOrigin.require(URI.create(rawOrigin), "origin").isLoopback()).isFalse();
    }

    @Test
    @DisplayName("기본 HTTPS 포트와 명시적 443 포트는 같은 출처다")
    void comparesOriginsUsingEffectivePorts() {
        HttpOrigin origin = HttpOrigin.require(URI.create("https://go.example"), "origin");
        HttpOrigin explicitDefault = HttpOrigin.require(
                URI.create("https://GO.example:443/"),
                "origin"
        );
        HttpOrigin differentPort = HttpOrigin.require(
                URI.create("https://go.example:444"),
                "origin"
        );

        assertThat(origin.sameOrigin(explicitDefault)).isTrue();
        assertThat(origin.sameOrigin(differentPort)).isFalse();
    }

    @Test
    @DisplayName("같은 출처의 스킴·호스트·기본 포트·루트 경로를 표준 형식으로 바꾼다")
    void canonicalizesEquivalentOriginRepresentations() {
        HttpOrigin canonical = HttpOrigin.require(
                URI.create("HTTPS://GO.Example:443/"),
                "origin"
        );

        assertThat(canonical.value()).isEqualTo(URI.create("https://go.example"));
    }

    @Test
    @DisplayName("같은 IPv6 주소 표기는 하나의 출처 URI로 통일한다")
    void canonicalizesEquivalentIpv6Representations() {
        HttpOrigin compressed = HttpOrigin.require(
                URI.create("https://[2001:db8::1]"),
                "origin"
        );
        HttpOrigin expanded = HttpOrigin.require(
                URI.create("https://[2001:0DB8:0:0:0:0:0:1]:443/"),
                "origin"
        );

        assertThat(compressed.value()).isEqualTo(expanded.value());
        assertThat(compressed.value())
                .isEqualTo(URI.create("https://[2001:db8:0:0:0:0:0:1]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user@go.example",
            "https://go.example/path",
            "https://go.example?query=value",
            "https://go.example#fragment",
            "https://go.example:",
            "https://go.example:0",
            "https://go.example:65536"
    })
    @DisplayName("출처 형식이 아닌 URI와 사용할 수 없는 포트는 거부한다")
    void rejectsValuesThatAreNotUsableOrigins(String rawOrigin) {
        assertThatThrownBy(() -> HttpOrigin.require(URI.create(rawOrigin), "origin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
