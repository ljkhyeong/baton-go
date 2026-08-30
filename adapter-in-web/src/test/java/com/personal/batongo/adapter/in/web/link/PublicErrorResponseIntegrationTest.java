package com.personal.batongo.adapter.in.web.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.in.web.ErrorResponse;
import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.adapter.in.web.WebMvcConfiguration;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = PublicErrorResponseIntegrationTest.WebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"
        })
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class PublicErrorResponseIntegrationTest {

    private static final String PUBLIC_CODE = "VOvLShvx93kQpj8x7w2HYQ";
    private static final String REQUEST_ID = "public-error-integration";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({ManagementApiSecurityConfiguration.class, FilterErrorResponseWriter.class,
            LinkResolverController.class, GlobalExceptionHandler.class, PublicLinkExceptionHandler.class,
            RequestIdFilter.class, WebMvcConfiguration.class, SimpleMeterRegistry.class})
    static class WebConfiguration {
    }

    @MockitoBean
    private SmartLinkUseCase useCase;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("비지원 응답 형식을 요청해도 미존재 링크는 JSON 404를 반환한다")
    void keepsNotFoundForUnsupportedResponseType() throws Exception {
        when(useCase.resolveLink(PUBLIC_CODE)).thenThrow(new LinkNotFoundException());

        HttpResponse<String> response = requestError(MediaType.APPLICATION_XML_VALUE);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(jsonMapper.readValue(response.body(), ErrorResponse.class)).isEqualTo(
                new ErrorResponse("LINK_NOT_FOUND", "링크를 찾을 수 없습니다", REQUEST_ID)
        );
    }

    @Test
    @DisplayName("HTML 요청의 서버 오류도 JSON으로 종료하고 서블릿 로그에 예외 원문을 노출하지 않는다")
    void keepsUnexpectedFailureRedactedForHtmlRequest(CapturedOutput output) throws Exception {
        String sensitiveMessage = "sensitive-exception-message";
        when(useCase.resolveLink(PUBLIC_CODE)).thenThrow(new IllegalStateException(sensitiveMessage));

        HttpResponse<String> response = requestError(MediaType.TEXT_HTML_VALUE);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(jsonMapper.readValue(response.body(), ErrorResponse.class)).isEqualTo(
                new ErrorResponse("INTERNAL_ERROR", "서버에서 요청을 처리하지 못했습니다", REQUEST_ID)
        );
        assertThat(output).contains(REQUEST_ID, IllegalStateException.class.getName())
                .doesNotContain(sensitiveMessage, PUBLIC_CODE);
    }

    private HttpResponse<String> requestError(String accept) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/l/" + PUBLIC_CODE))
                    .header(HttpHeaders.ACCEPT, accept).header("X-Request-Id", REQUEST_ID)
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                    .contains(MediaType.APPLICATION_JSON_VALUE);
            assertThat(response.headers().firstValue("X-Request-Id")).contains(REQUEST_ID);
            return response;
        }
    }
}
