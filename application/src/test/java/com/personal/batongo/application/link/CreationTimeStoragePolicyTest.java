package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.InvalidCreationTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CreationTimeStoragePolicyTest {

    private static final String ERROR_MESSAGE =
            "notBefore와 expiresAt은 1582-10-15T00:00:00Z 이상 "
                    + "9999-12-31T23:59:59.999999Z 이하의 마이크로초 단위여야 합니다";

    @Test
    @DisplayName("생성 시각 정책은 null과 지원 저장 범위의 양쪽 UTC 경계를 허용한다")
    void acceptsNullAndInclusiveDatetimeBoundaries() {
        assertThatCode(() -> CreationTimeStoragePolicy.requireStorable(null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> CreationTimeStoragePolicy.requireStorable(
                CreationTimeStoragePolicy.MINIMUM,
                CreationTimeStoragePolicy.MAXIMUM
        )).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("unstorableTimes")
    @DisplayName("생성 시각 정책은 JDBC 안전 범위 밖이거나 마이크로초보다 세밀한 값을 거부한다")
    void rejectsOutOfRangeAndSubMicrosecondTimes(Instant unstorableTime) {
        assertThatThrownBy(() -> CreationTimeStoragePolicy.requireStorable(
                unstorableTime,
                null
        ))
                .isExactlyInstanceOf(InvalidCreationTimeException.class)
                .hasMessage(ERROR_MESSAGE);
        assertThatThrownBy(() -> CreationTimeStoragePolicy.requireStorable(
                null,
                unstorableTime
        ))
                .isExactlyInstanceOf(InvalidCreationTimeException.class)
                .hasMessage(ERROR_MESSAGE);
    }

    @Test
    @DisplayName("생성 시각 정책은 범위 안의 과거 나노초 입력을 저장 불가 재생 후보로 구분한다")
    void distinguishesInRangeLegacyPrecisionFromOutOfRangeValues() {
        Instant legacyPrecision = Instant.parse("2026-07-30T10:00:00.123456001Z");

        assertThat(
                CreationTimeStoragePolicy.isWithinRange(legacyPrecision, null)
        ).isTrue();
        assertThat(
                CreationTimeStoragePolicy.isStorable(legacyPrecision, null)
        ).isFalse();
        assertThat(
                CreationTimeStoragePolicy.isWithinRange(
                        CreationTimeStoragePolicy.MAXIMUM.plus(1, ChronoUnit.MICROS),
                        null
                )
        ).isFalse();
    }

    private static Stream<Instant> unstorableTimes() {
        return Stream.of(
                CreationTimeStoragePolicy.MINIMUM.minus(1, ChronoUnit.MICROS),
                CreationTimeStoragePolicy.MAXIMUM.plus(1, ChronoUnit.MICROS),
                Instant.parse("2026-07-30T10:00:00.123456001Z")
        );
    }
}
