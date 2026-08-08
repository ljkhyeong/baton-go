package com.personal.batongo.bootstrap.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream standardError = new ByteArrayOutputStream();
        int exitCode = new LinkCodeKeyGuardBindingCli().run(
                args,
                environment,
                CANARY,
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
