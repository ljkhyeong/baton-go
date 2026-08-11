package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicLinkOriginTest {

    @Test
    @DisplayName("동등한 공개 origin 표기는 하나의 저장 문자열과 short URL로 정규화한다")
    void canonicalizesEquivalentOriginRepresentations() {
        PublicLinkOrigin origin = new PublicLinkOrigin(
                URI.create("HTTPS://GO.Example:443/")
        );

        assertThat(origin.serialized()).isEqualTo("https://go.example");
        assertThat(origin.shortUrl("abcdefghijklmnopqrstuv"))
                .isEqualTo(URI.create("https://go.example/l/abcdefghijklmnopqrstuv"));
    }

    @Test
    @DisplayName("DB 열에 보존할 수 없는 길이의 공개 origin은 설정 단계에서 거부한다")
    void rejectsOriginLongerThanPersistenceContract() {
        String oversizedHost = "a".repeat(244) + ".example";

        assertThatThrownBy(() -> new PublicLinkOrigin(
                URI.create("https://" + oversizedHost)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공개 base URL이 저장 가능한 길이를 초과합니다");
    }

    @Test
    @DisplayName("저장된 공개 origin은 재생 문자열을 바꿀 수 있는 비canonical 표기를 거부한다")
    void rejectsNonCanonicalStoredOrigin() {
        assertThatThrownBy(() -> PublicLinkOrigin.fromStored("HTTPS://GO.Example:443/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("저장된 공개 origin이 canonical 형식이 아닙니다");
    }

    @Test
    @DisplayName("공개 origin의 문자열 표현은 운영 주소를 노출하지 않는다")
    void redactsOriginFromStringRepresentation() {
        PublicLinkOrigin origin = new PublicLinkOrigin(URI.create("https://go.example"));

        assertThat(origin.toString())
                .isEqualTo("PublicLinkOrigin[redacted]")
                .doesNotContain("go.example");
    }
}
