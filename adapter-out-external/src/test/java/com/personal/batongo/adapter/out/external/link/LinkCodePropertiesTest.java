package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class LinkCodePropertiesTest {

    @Test
    @DisplayName("환경 변수의 키 묶음과 현재 발급 키는 표준 Spring 바인딩으로 읽는다")
    void bindsKeyRingFromEnvironmentVariables() {
        String secret = "test-secret-with-at-least-thirty-two-characters";
        var source = new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "BATONGO_LINKCODE_ACTIVEKEYID", "k202609",
                "BATONGO_LINKCODE_KEYS_K202609", secret
        ));
        var properties = new Binder(ConfigurationPropertySources.from(source))
                .bind("baton-go.link-code", Bindable.of(LinkCodeProperties.class)).get();
        assertThat(properties.activeKeyId()).isEqualTo("k202609");
        assertThat(properties.keys()).containsExactly(Map.entry("k202609", secret));
    }

    @Test
    @DisplayName("기본 키 환경 변수만 설정하면 default 키 ID를 현재 발급 키로 바인딩한다")
    void bindsDefaultKeyAsActiveKey() {
        String secret = "test-secret-with-at-least-thirty-two-characters";
        var source = new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "BATONGO_LINKCODE_KEYS_DEFAULT", secret
        ));
        var properties = new Binder(ConfigurationPropertySources.from(source))
                .bind("baton-go.link-code", Bindable.of(LinkCodeProperties.class)).get();
        assertThat(properties.activeKeyId()).isEqualTo("default");
        assertThat(properties.keys()).containsExactly(Map.entry("default", secret));
    }

    @Test
    @DisplayName("현재 발급 키 ID의 비밀값이 없으면 거부한다")
    void rejectsMissingActiveKey() {
        String secret = "test-secret-that-is-at-least-thirty-two-characters";
        assertThatThrownBy(() -> new LinkCodeProperties("missing", Map.of("default", secret)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "too-short"})
    @DisplayName("링크 코드 파생 비밀은 32자 이상이어야 한다")
    void rejectsMissingOrShortSecret(String invalidSecret) {
        assertThatThrownBy(() -> new LinkCodeProperties("default", Collections.singletonMap("default", invalidSecret)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("링크 코드 설정의 문자열 표현은 HMAC 비밀을 노출하지 않는다")
    void redactsSecretFromStringRepresentation() {
        String secret = "link-code-secret-that-must-never-be-logged";

        assertThat(new LinkCodeProperties("default", Map.of("default", secret)).toString())
                .doesNotContain(secret);
    }

    @Test
    @DisplayName("백업 복구 때 같은 비밀값을 쓰도록 파생 키의 공백과 줄바꿈을 유지한다")
    void preservesSecretSyntax() {
        String secret = " ".repeat(31) + "\n";

        assertThat(new LinkCodeProperties("default", Map.of("default", secret)).keys())
                .containsEntry("default", secret);
    }
}
