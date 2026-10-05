package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("멱등성 키는 버전 1~5의 소문자 RFC 표준 UUID를 허용한다")
    void acceptsSupportedCanonicalUuids(String value) {
        CreationIdempotencyKey key = CreationIdempotencyKey.parseRequest(value);

        assertThat(key.value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "1-1-1-1-1",
            "00000000-0000-4000-8000-00000000000",
            "8E448211-66AE-44AB-9888-C4960648C22B",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-7000-8000-000000000000",
            "00000000-0000-4000-7000-000000000000"
    })
    @DisplayName("대문자·nil·버전 6 이상·RFC 외 변형을 포함한 다른 UUID 표기는 거부한다")
    void rejectsNonCanonicalUuids(String value) {
        assertThatThrownBy(() -> CreationIdempotencyKey.parseRequest(value))
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class);
    }
}
