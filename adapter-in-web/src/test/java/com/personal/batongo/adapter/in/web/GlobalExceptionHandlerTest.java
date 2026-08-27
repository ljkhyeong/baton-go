package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("예상하지 못한 오류 로그는 예외 원문과 원인을 노출하지 않는다")
    void redactsUnexpectedExceptionDetailsFromLog(CapturedOutput output) {
        String sensitiveMessage = "sensitive-exception-message";
        String sensitiveCause = "sensitive-cause-message";

        new GlobalExceptionHandler(new SimpleMeterRegistry()).handleUnexpected(
                new IllegalStateException(
                        sensitiveMessage,
                        new RuntimeException(sensitiveCause)
                ),
                new MockHttpServletRequest()
        );

        assertThat(output)
                .contains(IllegalStateException.class.getName())
                .doesNotContain(sensitiveMessage)
                .doesNotContain(sensitiveCause);
    }
}
