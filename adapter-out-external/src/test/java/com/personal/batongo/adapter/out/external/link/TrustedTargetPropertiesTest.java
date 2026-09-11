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
    @DisplayName("설정된 대상 시스템의 허용 출처에서 경로를 만든다")
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
    @DisplayName("로컬 개발에서는 서로 다른 루프백 HTTP 출처를 허용한다")
    void acceptsSeparateLoopbackOriginsForLocalDevelopment() {
        assertThatCode(() -> new TrustedTargetProperties(
                URI.create("http://localhost:5173"),
                URI.create("http://127.0.0.1:5174")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("운영 BATON·ROUND 출처가 서로 다르면 거부한다")
    void rejectsDifferentOriginsOutsideLocalDevelopment() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("https://baton.example"),
                URI.create("https://round.example")
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("운영 대상의 HTTP 출처는 거부한다")
    void rejectsHttpOriginsOutsideLocalDevelopment() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("http://baton.example"),
                URI.create("http://baton.example")
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("루프백과 운영 대상 URL을 섞은 설정은 거부한다")
    void rejectsMixedLoopbackAndRemoteOrigins() {
        assertThatThrownBy(() -> new TrustedTargetProperties(
                URI.create("http://localhost:5173"),
                URI.create("https://baton.example")
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
