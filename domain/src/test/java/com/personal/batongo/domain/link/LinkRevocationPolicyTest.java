package com.personal.batongo.domain.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkRevocationPolicyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    @DisplayName("폐기 시각이 생성 시각보다 빠르면 생성 시각으로 맞춘다")
    void usesCreationTimeWhenClockIsBehind() {
        assertThat(LinkRevocationPolicy.firstRevocationAt(CREATED_AT, CREATED_AT.minusNanos(1_000)))
                .isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("폐기 시각이 생성 시각 이후면 그대로 사용한다")
    void keepsRevocationTimeAfterCreation() {
        Instant now = CREATED_AT.plusSeconds(1);

        assertThat(LinkRevocationPolicy.firstRevocationAt(CREATED_AT, now)).isEqualTo(now);
    }
}
