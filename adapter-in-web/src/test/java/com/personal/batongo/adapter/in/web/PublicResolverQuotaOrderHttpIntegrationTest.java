package com.personal.batongo.adapter.in.web;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.ResolveLinkUseCase;
import com.personal.batongo.application.link.port.out.PublicResolverQuotaPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(
        classes = PublicResolverHttpTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "baton-go.public-resolver-rate-limit.capacity=1",
                "baton-go.public-resolver-rate-limit.window=1h"
        }
)
@AutoConfigureRestTestClient
@DirtiesContext
class PublicResolverQuotaOrderHttpIntegrationTest {

    private static final String PATH = "/l/" + "A".repeat(22);

    @MockitoBean private ResolveLinkUseCase useCase;
    @MockitoBean private PublicResolverQuotaPort quota;
    @Autowired private RestTestClient client;

    @Test
    @DisplayName("로컬 한도를 넘은 요청은 분산 할당량을 쓰지 않고 429로 거부한다")
    void rejectsLocalOverflowBeforeDistributedQuota() {
        Mockito.when(useCase.resolveLink("A".repeat(22))).thenThrow(new LinkNotFoundException());

        client.get().uri(PATH).exchange().expectStatus().isNotFound();
        client.get().uri(PATH).exchange().expectStatus().isEqualTo(429);

        verify(quota, times(1)).acquireRetryAfterSeconds();
    }
}
