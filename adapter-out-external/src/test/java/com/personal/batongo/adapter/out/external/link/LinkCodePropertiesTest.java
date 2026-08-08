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
}
