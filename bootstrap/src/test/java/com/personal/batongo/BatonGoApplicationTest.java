package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatonGoApplicationTest {

    @Test
    @DisplayName("일반 애플리케이션은 guard 결합 확인 인자를 시작 전에 거부한다")
    void rejectsGuardBindingConfirmationArgument() {
        assertThatThrownBy(() -> BatonGoApplication.main(
                new String[]{"--confirm-writers-stopped"}
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
