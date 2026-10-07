package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.ResolveLinkUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

@SpringBootTest(
        classes = PublicResolverHttpTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "baton-go.public-resolver-rate-limit.capacity=1",
                "baton-go.public-resolver-rate-limit.window=15s"
        }
)
@AutoConfigureRestTestClient
@DirtiesContext
class PublicResolverRateLimitHttpIntegrationTest {

    private static final String FIRST_CODE = "first-link-code";
    private static final String LIMITED_CODE = "VOvLShvx93kQpj8x7w2HYQ";
    private static final String REQUEST_ID = "public-rate-limit-integration";

    @MockitoBean
    private ResolveLinkUseCase useCase;

    @Autowired
    private RestTestClient client;

    @Test
    @DisplayName("실제 HTTP 서버는 공개 링크 GET만 제한하고 요청 제한 응답 형식을 선택하며 HEAD 본문을 보내지 않는다")
    void rendersRateLimitResponsesThroughSpringMvc() {
        when(useCase.resolveLink(FIRST_CODE)).thenThrow(new LinkNotFoundException());

        // 허용량이 1이므로 아래 요청이 허용량을 쓰면 첫 GET이 404 대신 429가 된다.
        request(HttpMethod.POST, "/l/" + FIRST_CODE, MediaType.TEXT_HTML_VALUE).expectStatus().isEqualTo(405);
        request(HttpMethod.OPTIONS, "/l/" + FIRST_CODE, MediaType.ALL_VALUE).expectStatus().isOk();
        request(HttpMethod.GET, "/l/extra/segment", MediaType.APPLICATION_JSON_VALUE).expectStatus().isNotFound();
        request(HttpMethod.GET, "/unknown", MediaType.APPLICATION_JSON_VALUE).expectStatus().isNotFound();

        request(HttpMethod.GET, "/l/" + FIRST_CODE, MediaType.APPLICATION_JSON_VALUE).expectStatus().isNotFound();

        rateLimited(
                request(HttpMethod.GET, "/l/" + LIMITED_CODE, "application/json;q=0.3,text/html;q=0.9"),
                MediaType.TEXT_HTML_VALUE
        )
                .expectHeader().value("Content-Security-Policy", csp -> assertThat(csp).contains("default-src 'none'"))
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                .expectBody(String.class).value(body -> assertThat(body).contains(
                                "<html lang=\"ko\">",
                                "15초 후에 다시 열어 주세요",
                                "<a class=\"retry\" href=\"\">다시 열기</a>",
                                "<code>" + REQUEST_ID + "</code>"
                        )
                        .doesNotContain(LIMITED_CODE, "<script", "http-equiv=\"refresh\""));

        for (String accept : new String[]{
                MediaType.APPLICATION_JSON_VALUE,
                "text/html;q=0.3,application/json;q=0.9",
                MediaType.ALL_VALUE,
                MediaType.APPLICATION_XML_VALUE,
                "invalid"
        }) {
            rateLimited(request(HttpMethod.GET, "/l/" + LIMITED_CODE, accept), MediaType.APPLICATION_JSON_VALUE)
                    .expectBody(ErrorResponse.class).isEqualTo(new ErrorResponse(
                            "RATE_LIMIT_EXCEEDED",
                            "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요",
                            REQUEST_ID
                    ));
        }

        for (String accept : new String[]{
                MediaType.TEXT_HTML_VALUE,
                MediaType.APPLICATION_JSON_VALUE
        }) {
            rateLimited(request(HttpMethod.HEAD, "/l/" + LIMITED_CODE, accept), accept)
                    .expectBody().isEmpty();
        }

        verify(useCase, times(1)).resolveLink(FIRST_CODE);
    }

    private ResponseSpec rateLimited(ResponseSpec response, String contentType) {
        return response.expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "15")
                .expectHeader().value(HttpHeaders.VARY, vary -> assertThat(vary).isEqualTo(HttpHeaders.ACCEPT))
                .expectHeader().contentTypeCompatibleWith(contentType)
                .expectHeader().valueEquals("X-Request-Id", REQUEST_ID)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer");
    }

    private ResponseSpec request(HttpMethod method, String path, String accept) {
        return client.method(method).uri(path)
                .header(HttpHeaders.ACCEPT, accept)
                // 전달 주소를 바꿔도 전역 허용량을 우회하지 못하는지 함께 확인한다.
                .header("X-Forwarded-For", path.endsWith(FIRST_CODE) ? "198.51.100.1" : "198.51.100.2")
                .header("X-Request-Id", REQUEST_ID)
                .exchange();
    }
}
