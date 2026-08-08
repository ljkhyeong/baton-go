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
    @DisplayName("canonical loopback HTTP origin을 의미에 따라 판정한다")
    void recognizesCanonicalLoopbackOrigins(String rawOrigin) {
        assertThat(HttpOrigin.require(URI.create(rawOrigin), "origin").isLoopback()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://0127.0.0.1:8080",
            "http://127.00.0.1:8080",
            "http://127.0.0.1.example:8080",
            "https://go.example"
    })
    @DisplayName("모호한 주소와 비로컬 host는 loopback으로 분류하지 않는다")
    void rejectsAmbiguousLoopbackRepresentations(String rawOrigin) {
        assertThat(HttpOrigin.require(URI.create(rawOrigin), "origin").isLoopback()).isFalse();
    }

    @Test
    @DisplayName("기본 HTTPS 포트와 명시적 443 포트는 같은 origin이다")
    void comparesOriginsUsingEffectivePorts() {
        HttpOrigin origin = HttpOrigin.require(URI.create("https://go.example"), "origin");

        assertThat(origin.sameOrigin(URI.create("https://GO.example:443/l/code"))).isTrue();
        assertThat(origin.sameOrigin(URI.create("https://go.example:444/l/code"))).isFalse();
    }

    @Test
    @DisplayName("같은 IPv6 주소의 축약 표기와 확장 표기는 같은 origin이다")
    void comparesEquivalentIpv6LiteralOriginsByAddress() {
        HttpOrigin compressed = HttpOrigin.require(
                URI.create("https://[2001:db8::1]"),
                "origin"
        );
        HttpOrigin expanded = HttpOrigin.require(
                URI.create("https://[2001:0db8:0:0:0:0:0:1]"),
                "origin"
        );

        assertThat(compressed.sameOrigin(expanded)).isTrue();
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
    @DisplayName("origin이 아닌 URI와 사용할 수 없는 포트는 거부한다")
    void rejectsValuesThatAreNotUsableOrigins(String rawOrigin) {
        assertThatThrownBy(() -> HttpOrigin.require(URI.create(rawOrigin), "origin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("origin은 경로가 없는 HTTP 또는 HTTPS origin이어야 합니다");
    }
}
