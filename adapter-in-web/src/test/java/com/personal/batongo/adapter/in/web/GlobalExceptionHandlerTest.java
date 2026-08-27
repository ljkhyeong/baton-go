package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

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

        new GlobalExceptionHandler(new SimpleMeterRegistry()).handleUnexpected(
                exception,
                new MockHttpServletRequest()
        );

        assertThat(output)
                .contains(IllegalStateException.class.getName())
                .contains(RuntimeException.class.getName())
                .contains("com.personal.batongo.SafeService0#execute0:1")
                .contains("com.personal.batongo.SafeService11#execute11:12")
                .doesNotContain("com.personal.batongo.SafeService12#execute12:13")
                .doesNotContain(sensitiveMessage)
                .doesNotContain(sensitiveCause);
        assertThat(output.getOut().split(
                Pattern.quote(RuntimeException.class.getName()),
                -1
        ))
                .hasSize(9);
    }
}
