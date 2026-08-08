package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CreationIdempotencyKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "00000000-0000-1000-8000-000000000000",
            "00000000-0000-2000-9000-000000000000",
            "00000000-0000-3000-a000-000000000000",
            "00000000-0000-4000-b000-000000000000",
            "00000000-0000-5000-8000-000000000000"
    })
    @DisplayName("멱등성 키는 lowercase canonical UUID의 version 1부터 5와 RFC variant를 허용한다")
    void acceptsSupportedCanonicalUuids(String value) {
        CreationIdempotencyKey key = new CreationIdempotencyKey(value);

        assertThat(key.value()).isEqualTo(value);
        assertThat(key.meetsCurrentContract()).isTrue();
        assertThat(CreationIdempotencyKey.parseRequest(value).meetsCurrentContract())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8E448211-66AE-44AB-9888-C4960648C22B",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-7000-8000-000000000000",
            "00000000-0000-4000-7000-000000000000"
    })
    @DisplayName("요청 파서는 과거 canonical UUID를 재생 후보로만 정규화한다")
    void parsesHistoricallyAcceptedUuidsAsReplayOnly(String value) {
        CreationIdempotencyKey key = CreationIdempotencyKey.parseRequest(value);

        assertThat(key.value()).isEqualTo(value.toLowerCase());
        assertThat(key.meetsCurrentContract()).isFalse();
    }

    @Test
    @DisplayName("같은 정규화 값이어도 신규 가능 키와 재생 전용 키는 같지 않다")
    void keepsReplayOnlyStateInValueEquality() {
        CreationIdempotencyKey current = new CreationIdempotencyKey(
                "8e448211-66ae-44ab-9888-c4960648c22b"
        );
        CreationIdempotencyKey replayOnly = CreationIdempotencyKey.parseRequest(
                "8E448211-66AE-44AB-9888-C4960648C22B"
        );

        assertThat(replayOnly.value()).isEqualTo(current.value());
        assertThat(replayOnly).isNotEqualTo(current);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "8E448211-66AE-44AB-9888-C4960648C22B",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-8000-000000000000",
            "00000000-0000-6000-8000-000000000000",
            "00000000-0000-4000-7000-000000000000",
            "00000000-0000-4000-c000-000000000000"
    })
    @DisplayName("멱등성 키는 대문자와 nil, 지원하지 않는 version 및 non-RFC variant를 거부한다")
    void rejectsNonCanonicalOrUnsupportedUuids(String value) {
        assertThatThrownBy(() -> new CreationIdempotencyKey(value))
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Idempotency-Key는 canonical UUID 형식이어야 합니다");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "1-1-1-1-1",
            "00000000-0000-4000-8000-00000000000"
    })
    @DisplayName("요청 파서는 과거 계약에도 없던 UUID 표기를 거부한다")
    void rejectsFormsOutsideHistoricalContract(String value) {
        assertThatThrownBy(() -> CreationIdempotencyKey.parseRequest(value))
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Idempotency-Key는 canonical UUID 형식이어야 합니다");
    }
}
