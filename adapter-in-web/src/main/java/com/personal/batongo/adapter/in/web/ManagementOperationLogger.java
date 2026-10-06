package com.personal.batongo.adapter.in.web;

import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ManagementOperationLogger {

    private static final Logger LOG = LoggerFactory.getLogger(ManagementOperationLogger.class);

    private final ObjectWriter writer;

    public ManagementOperationLogger(JsonMapper jsonMapper) {
        this.writer = jsonMapper.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    public void completed(String operation, UUID linkId, Principal principal) {
        var event = new Event(operation, principal.getName(), linkId, RequestIdFilter.currentRequestId());
        LOG.info("관리 작업 완료 {}", writer.writeValueAsString(event));
    }

    /** Jackson 기본 설정(SORT_CREATOR_PROPERTIES_FIRST)이 구성요소 순서를 유지하므로 RUNBOOK 예시 순서로 둔다. */
    private record Event(String operation, String serviceId, UUID linkId, String requestId) {
    }
}
