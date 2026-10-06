package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

        String loggedRequestId = filter(request, response);

        String requestId = response.getHeader("X-Request-Id");
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        assertThat(loggedRequestId).isEqualTo(requestId);
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

        String loggedRequestId = filter(request, response);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo(candidate);
        assertThat(loggedRequestId).isEqualTo(candidate);
    }

    /** 요청 처리 중 로그·오류 응답에 쓰는 요청 ID를 반환하고, 요청이 끝나면 지워졌는지 확인한다. */
    private static String filter(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        var duringRequest = new AtomicReference<String>();
        new RequestIdFilter().doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> duringRequest.set(RequestIdFilter.currentRequestId())
        );
        assertThat(RequestIdFilter.currentRequestId()).isNull();
        return duringRequest.get();
    }
}
