package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.in.web.link.CreateLinkRequest;
import com.personal.batongo.adapter.in.web.operations.TargetContractRemediationRequest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class StrictHttpJsonConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(StrictHttpJsonConfiguration.class);

    @Test
    @DisplayName("Spring Boot ObjectMapper는 enum과 Long의 숫자 coercion을 거부한다")
    void configuresStrictCoercionOnSpringBootObjectMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            assertThatThrownBy(() -> objectMapper.readValue("""
                            {
                              "targetSystem": 0,
                              "targetPath": "/teams/team/seasons/season",
                              "purpose": "NAVIGATION"
                            }
                            """, CreateLinkRequest.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> objectMapper.readValue("""
                            {
                              "targetSystem": "0",
                              "targetPath": "/teams/team/seasons/season",
                              "purpose": "NAVIGATION"
                            }
                            """, CreateLinkRequest.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> objectMapper.readValue("""
                            {
                              "targetSystem": " BATON",
                              "targetPath": "/teams/team/seasons/season",
                              "purpose": "NAVIGATION"
                            }
                            """, CreateLinkRequest.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> objectMapper.readValue(
                            "{\"expectedVersion\":7.9}",
                            TargetContractRemediationRequest.class
                    ))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> objectMapper.readValue(
                            "{\"expectedVersion\":\"7\"}",
                            TargetContractRemediationRequest.class
                    ))
                    .isInstanceOf(JacksonException.class);
        });
    }

    @Test
    @DisplayName("Spring Boot ObjectMapper는 계약된 UTC 시각과 JSON 정수 token을 보존한다")
    void preservesContractedWireValuesOnSpringBootObjectMapper() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            CreateLinkRequest request = objectMapper.readValue("""
                    {
                      "targetSystem": "BATON",
                      "targetPath": "/teams/team/seasons/season",
                      "purpose": "NAVIGATION",
                      "notBefore": "2026-07-30T10:00:00.000000Z",
                      "expiresAt": "2026-07-30T10:00:00.123456789Z"
                    }
                    """, CreateLinkRequest.class);
            TargetContractRemediationRequest remediation = objectMapper.readValue(
                    "{\"expectedVersion\":7}",
                    TargetContractRemediationRequest.class
            );

            assertThat(request.notBefore())
                    .isEqualTo(Instant.parse("2026-07-30T10:00:00Z"));
            assertThat(request.expiresAt())
                    .isEqualTo(Instant.parse("2026-07-30T10:00:00.123456789Z"));
            assertThat(remediation.expectedVersion()).isEqualTo(7L);
        });
    }
}
