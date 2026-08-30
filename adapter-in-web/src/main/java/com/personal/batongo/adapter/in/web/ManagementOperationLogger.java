package com.personal.batongo.adapter.in.web;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("operation", operation);
        event.put("serviceId", principal.getName());
        event.put("linkId", linkId);
        event.put("requestId", MDC.get("requestId"));
        LOG.info("관리 작업 완료 {}", writer.writeValueAsString(event));
    }
}
