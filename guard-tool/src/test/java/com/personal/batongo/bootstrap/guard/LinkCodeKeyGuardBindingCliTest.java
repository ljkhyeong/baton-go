package com.personal.batongo.bootstrap.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkCodeKeyGuardBindingCliTest {

    private static final String CANARY = "8e448211-66ae-44ab-9888-c4960648c22b";

    @Test
    @DisplayName("writer 중지 확인 인자가 없으면 DB에 연결하지 않고 사용법만 반환한다")
    void requiresExplicitWriterStopConfirmation() {
        CapturedOutput output = run(new String[0], Map.of());

        assertThat(output.exitCode()).isEqualTo(LinkCodeKeyGuardBindingCli.EXIT_USAGE);
        assertThat(output.standardError()).contains("--confirm-writers-stopped");
        assertThat(output.combined()).doesNotContain(CANARY);
    }

    @Test
    @DisplayName("canary 입력이 없으면 설정 조회와 DB 연결 전에 안전하게 실패한다")
    void rejectsMissingCanaryBeforeRuntimeConfiguration() {
        Map<String, String> unreadableEnvironment = new HashMap<>() {
            @Override
            public String get(Object key) {
                throw new AssertionError("canary 검증 전에 설정을 조회하면 안 됩니다");
            }
        };

        CapturedOutput output = run(
                new String[]{"--confirm-writers-stopped"},
                unreadableEnvironment,
                null
        );

        assertThat(output.exitCode())
                .isEqualTo(LinkCodeKeyGuardBindingCli.EXIT_VERIFICATION_FAILED);
        assertThat(output.standardOutput()).isEmpty();
        assertThat(output.standardError())
                .contains("설정, canary와 DB 상태를 확인하세요");
    }

    @Test
    @DisplayName("필수 설정이 없으면 canary와 설정값을 노출하지 않고 안전하게 실패한다")
    void hidesSensitiveInputWhenConfigurationIsMissing() {
        CapturedOutput output = run(
                new String[]{"--confirm-writers-stopped"},
                Map.of("BATON_GO_LINK_CODE_SECRET", "secret-value-that-must-not-be-printed")
        );

        assertThat(output.exitCode())
                .isEqualTo(LinkCodeKeyGuardBindingCli.EXIT_VERIFICATION_FAILED);
        assertThat(output.standardError()).contains("설정, canary와 DB 상태를 확인하세요");
        assertThat(output.combined())
                .doesNotContain(CANARY)
                .doesNotContain("secret-value-that-must-not-be-printed")
                .doesNotContain("BATON_GO_DB_URL");
    }

    @Test
    @DisplayName("복구 CLI는 길이가 충분한 공백 링크 코드 비밀을 원문 그대로 허용한다")
    void acceptsLegacyWhitespaceLinkCodeSecretWithoutNormalization() {
        String legacySecret = " ".repeat(31) + "\n";

        LinkCodeKeyGuardBindingCli.RuntimeConfiguration configuration =
                LinkCodeKeyGuardBindingCli.RuntimeConfiguration.from(
                        validEnvironment(legacySecret)
                );

        assertThat(configuration.linkCodeProperties().secret()).isSameAs(legacySecret);
    }

    @Test
    @DisplayName("복구 CLI는 공개 예시와 다른 replace-with 접두사의 링크 코드 비밀을 허용한다")
    void acceptsNonPublishedSecretWithPlaceholderPrefix() {
        assertThatCode(() -> LinkCodeKeyGuardBindingCli.RuntimeConfiguration.from(
                validEnvironment("replace-with-a-real-link-code-secret-for-this-deployment")
        )).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "replace-with-at-least-32-random-characters",
            "replace-with-a-separate-at-least-32-character-secret"
    })
    @DisplayName("복구 CLI는 공개된 credential 예시값만 정확히 링크 코드 비밀에서 거부한다")
    void rejectsPublishedLinkCodeSecret(String publishedCredential) {
        assertThatThrownBy(() -> LinkCodeKeyGuardBindingCli.RuntimeConfiguration.from(
                validEnvironment(publishedCredential)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공개 예시 비밀은 사용할 수 없습니다");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "too-short"})
    @DisplayName("복구 CLI의 링크 코드 비밀 null과 길이는 공용 링크 코드 설정 계약으로 검증한다")
    void delegatesMissingAndShortLinkCodeSecretValidation(String invalidSecret) {
        assertThatThrownBy(() -> LinkCodeKeyGuardBindingCli.RuntimeConfiguration.from(
                validEnvironment(invalidSecret)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("링크 코드 파생 키는 32자 이상이어야 합니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    @DisplayName("복구 CLI의 데이터베이스 연결 설정은 공백 값을 거부한다")
    void rejectsBlankDatabaseConnectionSettings(String blankValue) {
        Map<String, String> environment = validEnvironment(
                "actual-link-code-secret-with-more-than-32-characters"
        );
        environment.put("BATON_GO_DB_URL", blankValue);

        assertThatThrownBy(() -> LinkCodeKeyGuardBindingCli.RuntimeConfiguration.from(
                environment
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BATON_GO_DB_URL 설정은 필수입니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8E448211-66AE-44AB-9888-C4960648C22B",
            "019AE750-9234-7ABC-8DEF-123456789ABC",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-4000-7000-000000000000"
    })
    @DisplayName("legacy canary는 과거 UUID 파서가 허용한 값을 lowercase canonical 문자열로 정규화한다")
    void normalizesLegacyCanariesAcceptedByPreviousParser(String rawValue) {
        LegacyCanaryIdempotencyKey parsed = LegacyCanaryIdempotencyKey.parse(rawValue);

        assertThat(parsed.value()).isEqualTo(rawValue.toLowerCase(Locale.ROOT));
        assertThat(parsed.toString())
                .isEqualTo("LegacyCanaryIdempotencyKey[redacted]")
                .doesNotContain(rawValue);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "1-1-1-1-1",
            " 8e448211-66ae-44ab-9888-c4960648c22b",
            "8e44821166ae44ab9888c4960648c22b",
            "not-a-uuid"
    })
    @DisplayName("legacy canary는 과거 UUID 파서가 거부한 비canonical 문자열을 거부한다")
    void rejectsValuesRejectedByPreviousParser(String rawValue) {
        assertThatThrownBy(() -> LegacyCanaryIdempotencyKey.parse(rawValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("legacy canary UUID 형식이 올바르지 않습니다");
    }

    private CapturedOutput run(String[] args, Map<String, String> environment) {
        return run(args, environment, CANARY);
    }

    private Map<String, String> validEnvironment(String linkCodeSecret) {
        Map<String, String> environment = new HashMap<>();
        environment.put("BATON_GO_LINK_CODE_SECRET", linkCodeSecret);
        environment.put("BATON_GO_DB_URL", "jdbc:mysql://localhost:3306/baton_go");
        environment.put("BATON_GO_DB_USERNAME", "baton_go");
        environment.put("BATON_GO_DB_PASSWORD", "database-password");
        return environment;
    }

    private CapturedOutput run(
            String[] args,
            Map<String, String> environment,
            String canary
    ) {
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();
        int exitCode = new LinkCodeKeyGuardBindingCli().run(
                args,
                environment,
                canary,
                new PrintStream(standardOutput, true, StandardCharsets.UTF_8),
                new PrintStream(standardError, true, StandardCharsets.UTF_8)
        );
        return new CapturedOutput(
                exitCode,
                standardOutput.toString(StandardCharsets.UTF_8),
                standardError.toString(StandardCharsets.UTF_8)
        );
    }

    private record CapturedOutput(
            int exitCode,
            String standardOutput,
            String standardError
    ) {

        private String combined() {
            return standardOutput + standardError;
        }
    }
}
