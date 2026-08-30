package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ManagementOperationLoggerTest {

    @Test
    @DisplayName("관리 이력은 허용 필드만 한 줄 JSON으로 기록하고 식별자의 줄바꿈을 이스케이프한다")
    void recordsOnlyAllowedFieldsWithoutLogInjection(CapturedOutput output) {
        var mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        var logger = new ManagementOperationLogger(mapper);
        String serviceId = "baton\"service\r\nforged-record";
        UUID linkId = UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");

        try (var ignored = MDC.putCloseable("requestId", "history-request")) {
            logger.completed("LINK_CREATE", linkId, () -> serviceId);
        }

        var lines = output.getOut().lines().filter(line -> line.contains("관리 작업 완료 ")).toList();
        assertThat(lines).hasSize(1);
        String line = lines.getFirst();
        var event = mapper.readTree(line.substring(line.indexOf("관리 작업 완료 ") + "관리 작업 완료 ".length()));
        assertThat(event.propertyNames())
                .containsExactlyInAnyOrder("operation", "serviceId", "linkId", "requestId");
        assertThat(event.get("serviceId").asString()).isEqualTo(serviceId);
        assertThat(event.get("linkId").asString()).isEqualTo(linkId.toString());
        assertThat(event.get("requestId").asString()).isEqualTo("history-request");
        assertThat(output).doesNotContain("\r\nforged-record");
    }

    @Test
    @DisplayName("서비스 식별자가 없는 기존 인증은 다른 값으로 추정하지 않고 null로 기록한다")
    void preservesMissingServiceIdentity(CapturedOutput output) {
        var logger = new ManagementOperationLogger(JsonMapper.builder().build());

        logger.completed("LINK_REVOKE", UUID.randomUUID(), () -> null);

        assertThat(output).contains("\"serviceId\":null");
    }
}
