package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkCodePropertiesTest {

    @Test
    @DisplayName("링크 코드 설정의 문자열 표현은 HMAC 비밀을 노출하지 않는다")
    void redactsSecretFromStringRepresentation() {
        String secret = "link-code-secret-that-must-never-be-logged";

        assertThat(new LinkCodeProperties(secret).toString())
                .doesNotContain(secret)
                .contains("redacted");
    }

    @Test
    @DisplayName("기존 데이터베이스 복구를 위해 링크 코드 파생 키의 원문 문법을 바꾸지 않는다")
    void preservesLegacySecretSyntax() {
        String legacySecret = " ".repeat(31) + "\n";

        assertThat(new LinkCodeProperties(legacySecret).secret()).isSameAs(legacySecret);
    }
}
