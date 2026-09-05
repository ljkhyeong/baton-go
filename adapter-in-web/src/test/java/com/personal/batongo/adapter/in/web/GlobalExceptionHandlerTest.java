package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkCreationReplayUnavailableException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "LINK_CREATION_REPLAY_UNAVAILABLE",
            "LINK_CODE_REPLAY_UNAVAILABLE",
            "LINK_CODE_CONFIGURATION_MISMATCH",
            "PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE"
    })
    @DisplayName("링크 복구 오류는 시작 시 등록한 오류 코드별 카운터에 한 번만 집계한다")
    void countsLinkRecoveryFailureByCode(String code) {
        var registry = new SimpleMeterRegistry();
        var handler = new GlobalExceptionHandler(registry);
        var counters = registry.find("baton.go.management.link.recovery.failures").counters();
        assertThat(counters).hasSize(4).allSatisfy(counter -> {
            assertThat(counter.count()).isZero();
            assertThat(counter.getId().getTags()).hasSize(1);
            assertThat(counter.getId().getTags().getFirst().getKey()).isEqualTo("code");
        });

        var request = new MockHttpServletRequest();
        var response = switch (code) {
            case "LINK_CREATION_REPLAY_UNAVAILABLE" -> handler.handleLinkCreationReplayUnavailable(
                    new LinkCreationReplayUnavailableException(UUID.randomUUID()), request
            );
            case "LINK_CODE_REPLAY_UNAVAILABLE" -> handler.handleLinkCodeReplayMismatch(
                    new LinkCodeReplayMismatchException(), request
            );
            case "LINK_CODE_CONFIGURATION_MISMATCH" -> handler.handleLinkCodeKeyBinding(
                    new LinkCodeKeyBindingException(), request
            );
            case "PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE" -> handler.handlePublicLinkOriginReplayUnavailable(
                    new PublicLinkOriginReplayUnavailableException(), request
            );
            default -> throw new IllegalArgumentException(code);
        };

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(counters).allSatisfy(counter -> assertThat(counter.count())
                .isEqualTo(code.equals(counter.getId().getTag("code")) ? 1.0 : 0.0));
    }

    @Test
    @DisplayName("예상하지 못한 오류 로그는 제한된 진단 정보만 남기고 원문을 노출하지 않는다")
    void redactsUnexpectedExceptionDetailsFromLog(CapturedOutput output) {
        String sensitiveMessage = "sensitive-exception-message";
        String sensitiveCause = "sensitive-cause-message";
        RuntimeException cause = null;
        for (int index = 0; index < 9; index++) {
            cause = new RuntimeException(sensitiveCause + index, cause);
        }
        IllegalStateException exception = new IllegalStateException(sensitiveMessage, cause);
        StackTraceElement[] stackFrames = new StackTraceElement[13];
        for (int index = 0; index < stackFrames.length; index++) {
            stackFrames[index] = new StackTraceElement(
                    "com.personal.batongo.SafeService" + index,
                    "execute" + index,
                    "SafeService.java",
                    index + 1
            );
        }
        exception.setStackTrace(stackFrames);

        var registry = new SimpleMeterRegistry();
        var response = new GlobalExceptionHandler(registry).handleUnexpected(
                exception,
                new MockHttpServletRequest()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(registry.find("baton.go.management.link.recovery.failures").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(response.getBody())
                .extracting(ErrorResponse::code, ErrorResponse::message)
                .containsExactly(
                        "INTERNAL_ERROR",
                        "서버에서 요청을 처리하지 못했습니다"
                );
        assertThat(output)
                .contains(IllegalStateException.class.getName())
                .contains(RuntimeException.class.getName())
                .contains("com.personal.batongo.SafeService0.execute0(SafeService.java:1)")
                .contains("com.personal.batongo.SafeService11.execute11(SafeService.java:12)")
                .doesNotContain("com.personal.batongo.SafeService12.execute12(SafeService.java:13)")
                .doesNotContain(sensitiveMessage)
                .doesNotContain(sensitiveCause);
        assertThat(output.getOut().split(
                Pattern.quote(RuntimeException.class.getName()),
                -1
        ))
                .hasSize(9);
    }
}
