package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.InvalidRequestException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreationRequestAdmissionPolicyTest {

    private static final Instant MAXIMUM_SUPPORTED_TIME =
            Instant.parse("9999-12-31T23:59:59.999999Z");

    @Test
    @DisplayName("MySQL 저장 범위 안의 마이크로초 시각은 허용한다")
    void allowsStorableMicrosecondTimes() {
        assertThatCode(() -> CreationRequestAdmissionPolicy.requireStorableTimes(
                Instant.parse("1582-10-15T00:00:00Z"),
                MAXIMUM_SUPPORTED_TIME
        )).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-08-08T01:02:03.123456789Z",
            "1582-10-14T23:59:59.999999Z",
            "+10000-01-01T00:00:00Z"
    })
    @DisplayName("마이크로초보다 정밀하거나 MySQL 저장 범위를 벗어난 시각은 절삭하지 않고 거부한다")
    void rejectsUnstorableTime(Instant value) {
        assertThatThrownBy(() -> CreationRequestAdmissionPolicy.requireStorableTimes(null, value))
                .isInstanceOf(InvalidRequestException.class);
    }
}
