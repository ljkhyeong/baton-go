package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicResolverRateLimitPropertiesTest {

    @Test
    @DisplayName("양수인 용량과 window는 공개 resolver rate limit 설정으로 허용한다")
    void acceptsPositiveCapacityAndWindow() {
        assertThatCode(() -> new PublicResolverRateLimitProperties(
                300,
                Duration.ofMinutes(1)
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("용량이 양수가 아니면 공개 resolver rate limit 설정을 거부한다")
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new PublicResolverRateLimitProperties(
                0,
                Duration.ofMinutes(1)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공개 resolver rate limit capacity는 양수여야 합니다");
    }

    @Test
    @DisplayName("window가 없거나 양수가 아니면 공개 resolver rate limit 설정을 거부한다")
    void rejectsMissingOrNonPositiveWindow() {
        assertThatThrownBy(() -> new PublicResolverRateLimitProperties(300, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공개 resolver rate limit window가 필요합니다");
        assertThatThrownBy(() -> new PublicResolverRateLimitProperties(
                300,
                Duration.ZERO
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공개 resolver rate limit window는 양수여야 합니다");
    }
}
