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
    @ValueSource(strings = {
            "",
            "contains space",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    })
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

    @ParameterizedTest
    @ValueSource(strings = {
            "request.ID_9-safe",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    })
    @DisplayName("허용 문자와 최대 길이를 지킨 요청 ID는 그대로 사용한다")
    void preservesSafeRequestId(String candidate) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", candidate);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> { }
        );

        assertThat(response.getHeader("X-Request-Id")).isEqualTo(candidate);
        assertThat(RequestIdFilter.requestId(request)).isEqualTo(candidate);
    }
}
