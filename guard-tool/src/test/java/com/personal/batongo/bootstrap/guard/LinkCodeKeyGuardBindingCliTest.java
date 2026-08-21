package com.personal.batongo.bootstrap.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkCodeKeyGuardBindingCliTest {

    private static final String CANARY = "8e448211-66ae-44ab-9888-c4960648c22b";

    @Test
    @DisplayName("writer 중지 확인 인자가 없으면 DB에 연결하지 않고 사용법만 반환한다")
    void requiresExplicitWriterStopConfirmation() {
        CapturedOutput output = run(new String[0], Map.of());

        assertThat(output.exitCode()).isEqualTo(2);
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
                .isEqualTo(3);
        assertThat(output.standardOutput()).isEmpty();
        assertThat(output.standardError())
                .contains("설정, canary와 DB 상태를 확인하세요");
    }

    @Test
    @DisplayName("필수 설정이 없거나 공백이면 비밀값을 노출하지 않고 안전하게 실패한다")
    void hidesSensitiveInputWhenConfigurationIsMissingOrBlank() {
        String secret = "secret-value-that-must-not-be-printed";
        Map<String, String> blankEnvironment = validEnvironment(secret);
        blankEnvironment.put("BATON_GO_DB_URL", " ");

        for (Map<String, String> environment : List.of(
                Map.of("BATON_GO_LINK_CODE_SECRET", secret),
                blankEnvironment
        )) {
            CapturedOutput output = run(
                    new String[]{"--confirm-writers-stopped"},
                    environment
            );

            assertThat(output.exitCode())
                    .isEqualTo(3);
            assertThat(output.combined())
                    .doesNotContain(CANARY)
                    .doesNotContain(secret)
                    .doesNotContain("database-password");
        }
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
