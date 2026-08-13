package com.personal.batongo.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkRevocationPolicyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    @DisplayName("최초 폐기는 생성 시각과 같거나 뒤인 요청 시각을 사용한다")
    void acceptsFirstRevocationAtOrAfterCreation() {
        Instant firstRevokedAt = CREATED_AT.plusSeconds(30);

        Instant result = LinkRevocationPolicy.requireFirstRevocationAt(
                CREATED_AT,
                firstRevokedAt
        );

        assertThat(result).isEqualTo(firstRevokedAt);
    }

    @Test
    @DisplayName("최초 폐기 시각이 생성 시각보다 빠르면 거부한다")
    void rejectsFirstRevocationBeforeCreation() {
        assertThatThrownBy(() -> LinkRevocationPolicy.requireFirstRevocationAt(
                CREATED_AT,
                CREATED_AT.minusNanos(1)
        ))
                .isInstanceOf(IllegalStateException.class);
    }
}
