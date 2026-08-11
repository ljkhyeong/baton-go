package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicLinkPropertiesTest {

    @Test
    @DisplayName("공개 base URL은 replay 저장에 사용할 canonical origin으로 정규화한다")
    void canonicalizesPublicOrigin() {
        PublicLinkProperties properties = new PublicLinkProperties(
                URI.create("HTTPS://GO.Example:443/")
        );

        assertThat(properties.publicBaseUrl()).isEqualTo(URI.create("https://go.example"));
        assertThat(properties.current().serialized()).isEqualTo("https://go.example");
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "https://go.example",
            "https://go.example/",
            "http://localhost:1",
            "http://127.0.0.2:8080",
            "http://[::1]:65535"
    })
    @DisplayName("포트가 없거나 명시적 포트가 허용 범위이면 공개 base URL을 허용한다")
    void acceptsOriginWithoutPortOrWithPortInRange(String rawUrl) {
        assertThatCode(() -> new PublicLinkProperties(URI.create(rawUrl)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "http://go.example",
            "http://127.0.0.1.example:8080",
            "http://0127.0.0.1:8080"
    })
    @DisplayName("비로컬 또는 모호한 HTTP 공개 origin은 거부한다")
    void rejectsNonLoopbackHttpOrigins(String rawUrl) {
        assertThatThrownBy(() -> new PublicLinkProperties(URI.create(rawUrl)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비로컬 공개 base URL은 HTTPS origin이어야 합니다");
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "https://go.example:",
            "https://go.example:0",
            "https://go.example:65536",
            "https://go.example:999999999999999999999",
            "http://[::1]:"
    })
    @DisplayName("명시적 포트가 1부터 65535 사이가 아니면 공개 base URL을 거부한다")
    void rejectsExplicitPortOutsideAllowedRange(String rawUrl) {
        assertThatThrownBy(() -> new PublicLinkProperties(URI.create(rawUrl)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공개 base URL은 경로가 없는 HTTP 또는 HTTPS origin이어야 합니다");
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("urlsWithDisallowedOriginComponents")
    @DisplayName("userinfo나 경로나 query나 fragment가 있으면 공개 base URL을 거부한다")
    void rejectsDisallowedOriginComponents(String boundary, String rawUrl) {
        assertThatThrownBy(() -> new PublicLinkProperties(URI.create(rawUrl)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공개 base URL은 경로가 없는 HTTP 또는 HTTPS origin이어야 합니다");
    }

    private static Stream<Arguments> urlsWithDisallowedOriginComponents() {
        return Stream.of(
                Arguments.of("userinfo", "https://user@go.example"),
                Arguments.of("빈 userinfo", "https://@go.example"),
                Arguments.of("경로", "https://go.example/path"),
                Arguments.of("인코딩된 경로", "https://go.example/%2F"),
                Arguments.of("query", "https://go.example?name=value"),
                Arguments.of("빈 query", "https://go.example?"),
                Arguments.of("fragment", "https://go.example#section"),
                Arguments.of("빈 fragment", "https://go.example#")
        );
    }
}
