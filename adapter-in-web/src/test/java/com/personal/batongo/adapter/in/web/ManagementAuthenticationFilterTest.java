package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ManagementAuthenticationFilterTest {

    private static final String TOKEN = "management-token-with-at-least-32-characters";

    private final ManagementAuthenticationFilter filter = new ManagementAuthenticationFilter(
            new ManagementProperties(TOKEN),
            new FilterErrorResponseWriter(new ObjectMapper())
    );

    @Test
    @DisplayName("관리 credential이 없으면 API 요청을 일관된 401 오류로 거부한다")
    void rejectsMissingCredential() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
        request.setRequestURI("/api/v1/links");
        request.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, "request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"MANAGEMENT_AUTHENTICATION_REQUIRED\"")
                .contains("\"requestId\":\"request-1\"");
    }

    @Test
    @DisplayName("정확한 Bearer credential이면 관리 API 요청을 다음 filter로 전달한다")
    void acceptsExactCredential() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
        request.setRequestURI("/api/v1/links");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
