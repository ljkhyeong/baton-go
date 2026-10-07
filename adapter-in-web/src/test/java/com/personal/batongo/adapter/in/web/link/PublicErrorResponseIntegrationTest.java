package com.personal.batongo.adapter.in.web.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.in.web.ErrorResponse;
import com.personal.batongo.adapter.in.web.PublicResolverHttpTestConfiguration;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.in.ResolveLinkUseCase;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy.Status;
import com.personal.batongo.domain.link.LinkUnavailableException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

@SpringBootTest(classes = PublicResolverHttpTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class PublicErrorResponseIntegrationTest {

    private static final String PUBLIC_CODE = "VOvLShvx93kQpj8x7w2HYQ";
    private static final String REQUEST_ID = "public-error-integration";
    private static final String HTML_UTF8 = "text/html;charset=UTF-8";

    @MockitoBean
    private ResolveLinkUseCase useCase;

    @Autowired
    private RestTestClient client;

    @Test
    @DisplayName("지원하지 않는 응답 형식을 요청해도 없는 링크는 JSON 404를 반환한다")
    void keepsNotFoundForUnsupportedResponseType() {
        when(useCase.resolveLink(PUBLIC_CODE)).thenThrow(new LinkNotFoundException());

        requestError(MediaType.APPLICATION_XML_VALUE, HttpMethod.GET)
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .expectBody(ErrorResponse.class)
                .isEqualTo(new ErrorResponse("LINK_NOT_FOUND", "링크를 찾을 수 없습니다", REQUEST_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json", "*/*", "application/xml"})
    @DisplayName("공개 서버 오류는 HTML 우선 요청만 안내 화면으로 응답하고 예외 원문을 숨긴다")
    void keepsUnexpectedFailureRedacted(String accept, CapturedOutput output) {
        String sensitiveMessage = "sensitive-exception-message";
        when(useCase.resolveLink(PUBLIC_CODE)).thenThrow(new IllegalStateException(sensitiveMessage));

        ResponseSpec response = requestError(accept, HttpMethod.GET).expectStatus().isEqualTo(500);

        if (MediaType.TEXT_HTML_VALUE.equals(accept)) {
            response.expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, HTML_UTF8)
                    .expectHeader().value("Content-Security-Policy",
                            csp -> assertThat(csp).contains("default-src 'none'"))
                    .expectHeader().value(HttpHeaders.VARY, vary -> assertThat(vary).isEqualTo(HttpHeaders.ACCEPT))
                    .expectBody(String.class).value(body -> assertThat(body).contains(
                            "<html lang=\"ko\">", "잠시 후 다시 열어 주세요", "<code>" + REQUEST_ID + "</code>",
                            "<a class=\"retry\" href=\"\">다시 열기</a>"
                    ));
        } else {
            response.expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .expectBody(ErrorResponse.class).isEqualTo(
                            new ErrorResponse("INTERNAL_ERROR", "서버에서 요청을 처리하지 못했습니다", REQUEST_ID)
                    );
        }
        response.expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain(sensitiveMessage, PUBLIC_CODE));
        assertThat(output).contains(REQUEST_ID, IllegalStateException.class.getName())
                .doesNotContain(sensitiveMessage, PUBLIC_CODE);
    }

    @ParameterizedTest
    @MethodSource("headErrors")
    @DisplayName("실제 HTTP 서버의 공개 오류 HEAD는 응답 형식을 유지하고 본문을 보내지 않는다")
    void returnsHeaderOnlyPublicError(Exception failure, String accept, int expectedStatus) {
        when(useCase.resolveLink(PUBLIC_CODE)).thenThrow(failure);

        requestError(accept, HttpMethod.HEAD)
                .expectStatus().isEqualTo(expectedStatus)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE,
                        MediaType.TEXT_HTML_VALUE.equals(accept) ? HTML_UTF8 : MediaType.APPLICATION_JSON_VALUE)
                .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                .expectBody().isEmpty();
    }

    @Test
    @DisplayName("실제 HTTP 서버의 관리 인증 오류 HEAD는 JSON 형식과 인증 안내를 유지하고 본문을 보내지 않는다")
    void returnsHeaderOnlyManagementAuthenticationError() {
        send(HttpMethod.HEAD, "/api/v1/links/83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae", MediaType.APPLICATION_JSON_VALUE)
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"baton-go-management\"")
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .expectBody().isEmpty();
    }

    private static Stream<Arguments> headErrors() {
        return Stream.of(
                Arguments.of(new LinkUnavailableException(Status.NOT_ACTIVE,
                        "아직 사용할 수 없는 링크입니다", Instant.parse("2026-07-29T15:30:00Z")),
                        MediaType.TEXT_HTML_VALUE, 404),
                Arguments.of(new LinkUnavailableException(Status.EXPIRED, "만료된 링크입니다", null),
                        MediaType.TEXT_HTML_VALUE, 410),
                Arguments.of(new IllegalStateException("서버 오류"), MediaType.TEXT_HTML_VALUE, 500),
                Arguments.of(new StoredTargetPolicyViolationException(UUID.fromString(
                        "70f147f2-b02a-4a63-bc27-bf60e44db591"
                )), MediaType.APPLICATION_JSON_VALUE, 404)
        );
    }

    private ResponseSpec requestError(String accept, HttpMethod method) {
        return send(method, "/l/" + PUBLIC_CODE, accept);
    }

    private ResponseSpec send(HttpMethod method, String path, String accept) {
        return client.method(method).uri(path)
                .header(HttpHeaders.ACCEPT, accept)
                .header("X-Request-Id", REQUEST_ID)
                .exchange()
                .expectHeader().valueEquals("X-Request-Id", REQUEST_ID)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
    }
}
