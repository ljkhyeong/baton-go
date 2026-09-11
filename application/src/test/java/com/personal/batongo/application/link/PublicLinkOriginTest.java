package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicLinkOriginTest {

    @Test
    @DisplayName("공개 출처로 저장값과 단축 URL을 만든다")
    void serializesOriginAndBuildsShortUrl() {
        PublicLinkOrigin origin = new PublicLinkOrigin(
                URI.create("https://go.example")
        );

        assertThat(origin.serialized()).isEqualTo("https://go.example");
        assertThat(origin.shortUrl("abcdefghijklmnopqrstuv"))
                .isEqualTo(URI.create("https://go.example/l/abcdefghijklmnopqrstuv"));
    }

    @Test
    @DisplayName("255자를 넘는 공개 출처는 설정할 수 없다")
    void rejectsOriginLongerThanPersistenceContract() {
        String oversizedHost = "a".repeat(244) + ".example";

        assertThatThrownBy(() -> new PublicLinkOrigin(
                URI.create("https://" + oversizedHost)
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("운영 공개 출처는 HTTPS만 허용한다")
    void rejectsNonHttpsRemoteOrigin() {
        assertThatThrownBy(() -> new PublicLinkOrigin(URI.create("http://go.example")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("저장된 공개 출처는 표준 URL 형식만 허용한다")
    void rejectsNonCanonicalStoredOrigin() {
        assertThatThrownBy(() -> PublicLinkOrigin.fromStored("HTTPS://GO.Example:443/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
