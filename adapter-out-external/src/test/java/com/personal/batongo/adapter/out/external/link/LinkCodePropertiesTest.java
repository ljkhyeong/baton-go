package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkCodePropertiesTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "too-short"})
    @DisplayName("링크 코드 파생 비밀은 32자 이상이어야 한다")
    void rejectsMissingOrShortSecret(String invalidSecret) {
        assertThatThrownBy(() -> new LinkCodeProperties(invalidSecret))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("링크 코드 설정의 문자열 표현은 HMAC 비밀을 노출하지 않는다")
    void redactsSecretFromStringRepresentation() {
        String secret = "link-code-secret-that-must-never-be-logged";

        assertThat(new LinkCodeProperties(secret).toString())
                .doesNotContain(secret);
    }

    @Test
    @DisplayName("기존 데이터베이스 복구를 위해 링크 코드 파생 키의 원문 문법을 바꾸지 않는다")
    void preservesLegacySecretSyntax() {
        String legacySecret = " ".repeat(31) + "\n";

        assertThat(new LinkCodeProperties(legacySecret).secret()).isEqualTo(legacySecret);
    }

    @Test
    @DisplayName("이미 공개된 링크 코드 비밀 예시값은 거부한다")
    void rejectsPublishedSecret() {
        assertThatThrownBy(() -> new LinkCodeProperties(
                "replace-with-a-separate-at-least-32-character-secret"
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
