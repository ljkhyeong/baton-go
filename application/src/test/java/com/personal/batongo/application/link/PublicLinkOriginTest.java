package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicLinkOriginTest {

    @Test
    @DisplayName("공개 origin은 저장 문자열과 short URL을 조립한다")
    void serializesOriginAndBuildsShortUrl() {
        PublicLinkOrigin origin = new PublicLinkOrigin(
                URI.create("https://go.example")
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
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("비로컬 공개 origin은 HTTPS가 아니면 거부한다")
    void rejectsNonHttpsRemoteOrigin() {
        assertThatThrownBy(() -> new PublicLinkOrigin(URI.create("http://go.example")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("저장된 공개 origin은 재생 문자열을 바꿀 수 있는 비canonical 표기를 거부한다")
    void rejectsNonCanonicalStoredOrigin() {
        assertThatThrownBy(() -> PublicLinkOrigin.fromStored("HTTPS://GO.Example:443/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("공개 origin의 문자열 표현은 운영 주소를 노출하지 않는다")
    void redactsOriginFromStringRepresentation() {
        PublicLinkOrigin origin = new PublicLinkOrigin(URI.create("https://go.example"));

        assertThat(origin.toString())
                .doesNotContain("go.example");
    }
}
