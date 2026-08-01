package com.personal.batongo.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class FilterErrorResponseWriter {

    private static final String MANAGEMENT_BEARER_CHALLENGE =
            "Bearer realm=\"baton-go-management\"";

    private final ObjectMapper objectMapper;

    public FilterErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, MANAGEMENT_BEARER_CHALLENGE);
        }
        objectMapper.writeValue(
                response.getOutputStream(),
                new ErrorResponse(code, message, RequestIdFilter.requestId(request))
        );
    }
}
