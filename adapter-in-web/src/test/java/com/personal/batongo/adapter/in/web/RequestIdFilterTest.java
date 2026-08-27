package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "contains space")
    @DisplayName("누락되거나 안전하지 않은 요청 ID는 새 UUID로 교체한다")
    void replacesMissingOrUnsafeRequestId(String candidate) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (candidate != null) {
            request.addHeader("X-Request-Id", candidate);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> { }
        );

        String requestId = response.getHeader("X-Request-Id");
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        assertThat(RequestIdFilter.requestId(request)).isEqualTo(requestId);
    }
}
