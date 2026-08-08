package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatonGoApplicationTest {

    @Test
    @DisplayName("일반 애플리케이션은 guard 결합 확인 인자를 Spring 시작 전에 거부한다")
    void rejectsGuardBindingConfirmationArgument() {
        assertThatThrownBy(() -> BatonGoApplication.requireNormalApplicationArguments(
                new String[]{"--confirm-writers-stopped"}
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("guard 결합 확인 인자는 전용 guard binding 도구에서만 사용할 수 있습니다");
    }

    @Test
    @DisplayName("일반 애플리케이션은 guard 결합용이 아닌 Spring 인자를 허용한다")
    void acceptsNormalApplicationArguments() {
        assertThatCode(() -> BatonGoApplication.requireNormalApplicationArguments(
                new String[]{"--spring.profiles.active=local"}
        )).doesNotThrowAnyException();
    }
}
