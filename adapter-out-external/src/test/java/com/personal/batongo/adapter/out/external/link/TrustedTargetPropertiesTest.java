package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrustedTargetPropertiesTest {

    @Test
    @DisplayName("설정된 대상 시스템의 신뢰 origin 안에서 경로를 해석한다")
    void resolvesWithinConfiguredOrigins() {
        TrustedTargetProperties properties = new TrustedTargetProperties(
                URI.create("http://localhost:3000"),
                URI.create("http://localhost:3001")
        );
        String batonTarget = "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

        assertThat(properties.resolve(TrustedTargetPolicy.requireAllowed(
                TargetSystem.BATON,
                LinkPurpose.NAVIGATION,
                batonTarget
        )))
                .isEqualTo(URI.create("http://localhost:3000" + batonTarget));
        assertThat(properties.resolve(TrustedTargetPolicy.requireAllowed(
                TargetSystem.ROUND,
                LinkPurpose.MEETING_ENTRY,
                "/room/abcd-efgh-jkmp"
        )))
                .isEqualTo(URI.create("http://localhost:3001/room/abcd-efgh-jkmp"));
    }

    @Test
    @DisplayName("로컬 개발의 서로 다른 loopback HTTP origin은 허용한다")
    void acceptsSeparateLoopbackOriginsForLocalDevelopment() {
        assertThatCode(() -> new TrustedTargetProperties(
                URI.create("http://localhost:5173"),
                URI.create("http://127.0.0.1:5174")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비로컬 target의 서로 다른 origin은 거부한다")
    void rejectsDifferentOriginsOutsideLocalDevelopment() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("https://baton.example"),
                URI.create("https://round.example")
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비로컬 target의 HTTP origin은 거부한다")
    void rejectsHttpOriginsOutsideLocalDevelopment() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("http://baton.example"),
                URI.create("http://baton.example")
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("loopback과 비로컬 target을 섞은 설정은 거부한다")
    void rejectsMixedLoopbackAndRemoteOrigins() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("http://localhost:5173"),
                URI.create("https://baton.example")
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
