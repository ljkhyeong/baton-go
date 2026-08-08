package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
