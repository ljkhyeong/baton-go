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
    @DisplayName("Bearer 인증 스킴 뒤의 탭은 SP separator로 허용하지 않는다")
    void rejectsNonSpaceSeparator() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
        request.setRequestURI("/api/v1/links");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer\t" + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

}
