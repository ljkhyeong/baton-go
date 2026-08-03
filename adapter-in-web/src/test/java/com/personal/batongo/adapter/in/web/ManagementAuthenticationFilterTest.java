package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class ManagementAuthenticationFilterTest {

    private static final String TOKEN = "management-token-with-at-least-32-characters";
    private static final String BEARER_CHALLENGE =
            "Bearer realm=\"baton-go-management\"";

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
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(BEARER_CHALLENGE);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"MANAGEMENT_AUTHENTICATION_REQUIRED\"")
                .contains("\"requestId\":\"request-1\"");
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {"Bearer", "bearer", "BEARER", "BeArEr"})
    @DisplayName("Bearer 인증 스킴은 대소문자와 관계없이 관리 API 요청을 인증한다")
    void acceptsCaseInsensitiveBearerScheme(String scheme) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
        request.setRequestURI("/api/v1/links");
        request.addHeader(HttpHeaders.AUTHORIZATION, scheme + " " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(ints = {1, 2, 4})
    @DisplayName("Bearer 인증 스킴 뒤의 연속 공백 개수와 관계없이 관리 credential을 인식한다")
    void acceptsOneOrMoreSpacesBeforeCredential(int spaceCount) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
        request.setRequestURI("/api/v1/links");
        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer" + " ".repeat(spaceCount) + TOKEN
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {"Bearer\t", "Bearer"})
    @DisplayName("Bearer 인증 스킴 뒤에 SP가 없으면 관리 credential을 거부한다")
    void rejectsCredentialWithoutSpaceSeparator(String schemeAndSeparator) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
        request.setRequestURI("/api/v1/links");
        request.addHeader(HttpHeaders.AUTHORIZATION, schemeAndSeparator + TOKEN);
        request.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, "request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(BEARER_CHALLENGE);
    }

    @Test
    @DisplayName("잘못된 관리 credential은 Bearer challenge와 함께 401 오류로 거부한다")
    void rejectsInvalidCredentialWithBearerChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/links");
        request.setRequestURI("/api/v1/links");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-management-token");
        request.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, "request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(BEARER_CHALLENGE);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"MANAGEMENT_AUTHENTICATION_REQUIRED\"");
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "/api/v1;x/links",
            "/api/v%31/links",
            "/api/v1/links;x"
    })
    @DisplayName("경로 표현이 달라도 관리 API이면 credential을 요구한다")
    void protectsParsedManagementPath(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, "request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(BEARER_CHALLENGE);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"MANAGEMENT_AUTHENTICATION_REQUIRED\"");
    }

    @Test
    @DisplayName("공개 링크 경로는 관리 credential 없이 다음 filter로 전달한다")
    void leavesPublicLinkUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/l/VOvLShvx93kQpj8x7w2HYQ"
        );
        request.setRequestURI("/l/VOvLShvx93kQpj8x7w2HYQ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
