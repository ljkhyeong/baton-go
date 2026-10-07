package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    @DisplayName("운영 BATON·ROUND가 표준화 후 같은 HTTPS 출처면 허용한다")
    void acceptsSameHttpsOriginOutsideLocalDevelopment() {
        TrustedTargetProperties properties = new TrustedTargetProperties(
                URI.create("https://baton.example"),
                URI.create("https://BATON.example:443")
        );

        assertThat(properties.resolve(TrustedTargetPolicy.requireAllowed(
                TargetSystem.ROUND,
                LinkPurpose.MEETING_ENTRY,
                "/room/abcd-efgh-jkmp"
        )))
                .isEqualTo(URI.create("https://baton.example/room/abcd-efgh-jkmp"));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
            "서로 다른 HTTPS 출처, https://baton.example, https://round.example",
            "HTTP 출처, http://baton.example, http://baton.example",
            "루프백과 운영 출처 혼합, http://localhost:5173, https://baton.example"
    })
    @DisplayName("운영 BATON·ROUND 기본 URL이 같은 HTTPS 출처가 아니면 거부한다")
    void rejectsOriginsOutsideSameHttpsOrigin(String description, URI batonBaseUrl, URI roundBaseUrl) {
        assertThatThrownBy(() -> new TrustedTargetProperties(batonBaseUrl, roundBaseUrl))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
