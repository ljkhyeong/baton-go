package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.CreationRequestAdmissionPolicy.ReplayOnlyReason;
import com.personal.batongo.application.link.error.InvalidCreationTimeException;
import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreationRequestAdmissionPolicyTest {

    private static final CreationIdempotencyKey CURRENT_KEY =
            new CreationIdempotencyKey("8e448211-66ae-44ab-9888-c4960648c22b");

    @Test
    @DisplayName("현재 계약의 키와 마이크로초 시각은 신규 예약 진입을 허용한다")
    void allowsCurrentRequestToCreateReservation() {
        Instant expiresAt = Instant.parse("2026-08-08T01:02:03.123456Z");

        var decision = CreationRequestAdmissionPolicy.evaluate(
                CURRENT_KEY,
                null,
                expiresAt
        );

        assertThat(decision.allowsNewReservation()).isTrue();
        assertThat(decision.replayOnlyReason()).isEqualTo(ReplayOnlyReason.NONE);
        assertThat(decision.expiresAt()).isEqualTo(expiresAt);
        assertThat(decision.toString()).isEqualTo(
                "CreationRequestAdmissionPolicy.Decision[redacted]"
        );
    }

    @Test
    @DisplayName("과거 키는 기존 예약만 조회하고 예약 부재를 키 오류로 분류한다")
    void classifiesLegacyKeyAsReplayOnly() {
        CreationIdempotencyKey legacyKey = CreationIdempotencyKey.parseRequest(
                "8E448211-66AE-44AB-9888-C4960648C22B"
        );

        var decision = CreationRequestAdmissionPolicy.evaluate(
                legacyKey,
                null,
                null
        );

        assertThat(decision.allowsNewReservation()).isFalse();
        assertThat(decision.replayOnlyReason())
                .isEqualTo(ReplayOnlyReason.LEGACY_IDEMPOTENCY_KEY);
        assertThat(decision.missingReservationException())
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    @DisplayName("과거 나노초 시각은 마이크로초로 정규화해 재생 후보로만 분류한다")
    void normalizesSubMicrosecondTimeForReplayOnlyComparison() {
        Instant historicalTime = Instant.parse("2026-08-08T01:02:03.123456789Z");

        var decision = CreationRequestAdmissionPolicy.evaluate(
                CURRENT_KEY,
                null,
                historicalTime
        );

        assertThat(decision.allowsNewReservation()).isFalse();
        assertThat(decision.replayOnlyReason())
                .isEqualTo(ReplayOnlyReason.SUB_MICROSECOND_TIME);
        assertThat(decision.expiresAt())
                .isEqualTo(Instant.parse("2026-08-08T01:02:03.123456Z"));
        assertThat(decision.missingReservationException())
                .isExactlyInstanceOf(InvalidCreationTimeException.class);
    }

    @Test
    @DisplayName("과거 키와 나노초 시각이 겹치면 기존 키 오류 우선순위를 유지한다")
    void prioritizesLegacyKeyWhenReplayOnlyReasonsOverlap() {
        CreationIdempotencyKey legacyKey = CreationIdempotencyKey.parseRequest(
                "00000000-0000-7000-8000-00000000000A"
        );

        var decision = CreationRequestAdmissionPolicy.evaluate(
                legacyKey,
                null,
                Instant.parse("2026-08-08T01:02:03.123456789Z")
        );

        assertThat(decision.replayOnlyReason())
                .isEqualTo(ReplayOnlyReason.LEGACY_IDEMPOTENCY_KEY);
        assertThat(decision.missingReservationException())
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    @DisplayName("지원 범위 밖 시각은 과거 키 여부보다 먼저 거부한다")
    void rejectsOutOfRangeTimeBeforeReplayCompatibility() {
        CreationIdempotencyKey legacyKey = CreationIdempotencyKey.parseRequest(
                "00000000-0000-7000-8000-00000000000A"
        );

        assertThatThrownBy(() -> CreationRequestAdmissionPolicy.evaluate(
                legacyKey,
                null,
                CreationTimeStoragePolicy.MAXIMUM.plusNanos(1_000)
        )).isExactlyInstanceOf(InvalidCreationTimeException.class);
    }
}
