package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.port.in.ResolveLinkUseCase;
import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(
        classes = PublicResolverHttpTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureRestTestClient
@DirtiesContext
class DistributedResolverQuotaHttpIntegrationTest {

    private static final String PATH = "/l/" + "A".repeat(22);

    @MockitoBean private ResolveLinkUseCase useCase;
    @MockitoBean private PublicResolverQuotaPort quota;
    @Autowired private RestTestClient client;

    @Test
    @DisplayName("분산 제한의 429와 장애 503은 공개 JSON·HTML에 표시하고 링크 조회를 실행하지 않는다")
    void blocksBeforeLinkLookup() {
        when(quota.acquireRetryAfterSeconds()).thenReturn(7L);
        client.get().uri(PATH).exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "7");

        when(quota.acquireRetryAfterSeconds()).thenThrow(new PublicResolverQuotaUnavailableException());
        client.get().uri(PATH).exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("RATE_LIMIT_UNAVAILABLE", "지금은 링크 요청을 처리할 수 없습니다"));
        client.get().uri(PATH).header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE).exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("지금은 링크를 열 수 없습니다.", "잠시 후 다시 열어 주세요",
                                "<a class=\"retry\" href=\"\">다시 열기</a>")
                        .doesNotContain("지금은 링크 요청을 처리할 수 없습니다"));
        client.head().uri(PATH).header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE).exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().isEmpty();
        verifyNoInteractions(useCase);
    }
}
