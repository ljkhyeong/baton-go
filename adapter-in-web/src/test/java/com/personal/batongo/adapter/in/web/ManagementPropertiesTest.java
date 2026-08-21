package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ManagementPropertiesTest {

    @Test
    @DisplayName("관리 설정의 문자열 표현은 credential을 노출하지 않는다")
    void redactsCredentialFromStringRepresentation() {
        String token = "management-token-with-at-least-32-characters";

        assertThat(new ManagementProperties(token).toString()).doesNotContain(token);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("missingOrShortCredentials")
    @DisplayName("관리 credential이 없거나 32자보다 짧으면 거부한다")
    void rejectsMissingOrShortCredential(String boundary, String token) {
        assertThatThrownBy(() -> new ManagementProperties(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("credentialsOutsidePrintableAscii")
    @DisplayName("관리 credential이 출력 가능한 ASCII 범위를 벗어나면 거부한다")
    void rejectsCredentialOutsidePrintableAscii(String boundary, String token) {
        assertThatThrownBy(() -> new ManagementProperties(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 공개된 관리 credential 예시값은 거부한다")
    void rejectsPublishedCredential() {
        assertThatThrownBy(() -> new ManagementProperties(
                "replace-with-at-least-32-random-characters"
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> missingOrShortCredentials() {
        return Stream.of(
                Arguments.of("누락", null),
                Arguments.of("31자", "a".repeat(31))
        );
    }

    private static Stream<Arguments> credentialsOutsidePrintableAscii() {
        return Stream.of(
                Arguments.of("ASCII 공백", "a".repeat(31) + " "),
                Arguments.of("DEL", "a".repeat(31) + "\u007f")
        );
    }
}
