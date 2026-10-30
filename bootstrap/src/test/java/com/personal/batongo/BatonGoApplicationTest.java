package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatonGoApplicationTest {

    @Test
    @DisplayName("업무 시계는 DATETIME(6)에 그대로 저장되도록 UTC 마이크로초 단위로 시각을 낸다")
    void clockTicksInUtcMicroseconds() {
        Clock clock = new BatonGoApplication().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.instant().getNano() % 1_000).isZero();
    }
}
